package org.example.authorizationservice.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.authorizationservice.core.config.InternalTokenProperties;
import org.example.authorizationservice.core.exception.UnknownResourceException;
import org.example.authorizationservice.model.UserEntity;
import org.example.authorizationservice.repository.UserRepository;
import org.example.authorizationservice.support.DpopProofs;
import org.example.grpc.auth.InvalidReason;
import org.example.grpc.auth.ResolvedToken;
import org.example.grpc.auth.TokenExchangeResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TokenExchangeServiceTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final String OPAQUE_TOKEN = "opaque-access-token";
    private static final String USERNAME = "demo-user";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Duration TTL = Duration.ofSeconds(60);

    private static RSAKey signingKey;
    private static ECKey dpopKey;
    private static String jkt;

    private final OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
    private final RegisteredClientRepository registeredClientRepository = mock(RegisteredClientRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final RegisteredClient client = RegisteredClient.withId("registered-client-id")
            .clientId("demo-client")
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://client.example.com/callback")
            .build();

    private TokenExchangeService service;

    @BeforeAll
    static void generateKeys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("signing-key").generate();
        dpopKey = new ECKeyGenerator(Curve.P_256).generate();
        jkt = dpopKey.computeThumbprint().toString();
    }

    @BeforeEach
    void setUp() {
        service = new TokenExchangeService(
                authorizationService,
                registeredClientRepository,
                userRepository,
                new DpopProofVerifier(),
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey))),
                AuthorizationServerSettings.builder().issuer(ISSUER).build(),
                new InternalTokenProperties(TTL, Set.of("ledger-service", "payment-service")));

        when(registeredClientRepository.findById(client.getId())).thenReturn(client);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(true)));
    }

    @Test
    void mintsTokenForOneAudienceBoundToTheUserAndTheirKey() throws Exception {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of("jkt", jkt));

        TokenExchangeResponse response = service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service");

        assertThat(response.getResultCase()).isEqualTo(TokenExchangeResponse.ResultCase.RESOLVED);
        ResolvedToken resolved = response.getResolved();

        SignedJWT jwt = SignedJWT.parse(resolved.getAccessToken());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(jwt.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.PS256);
        assertThat(jwt.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("signing-key");

        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.getAudience()).containsExactly("ledger-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("demo-client");
        assertThat(claims.getStringClaim("scope").split(" ")).containsExactlyInAnyOrder("openid", "profile");
        assertThat(claims.getJSONObjectClaim("cnf")).containsEntry("jkt", jkt);
        assertThat(claims.getJWTID()).isNotBlank();

        Instant issuedAt = claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();

        assertThat(Duration.between(issuedAt, expiresAt)).isEqualTo(TTL);
        assertThat(resolved.getCnfJkt()).isEqualTo(jkt);
        assertThat(resolved.getExpiresAt().getSeconds()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    void neverOutlivesOpaqueToken() throws Exception {
        Instant opaqueTokenExpiry = Instant.now().plus(Duration.ofSeconds(20));
        storeAuthorization(opaqueTokenExpiry, false, Map.of("jkt", jkt));

        TokenExchangeResponse response = service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service");

        Instant expiresAt = SignedJWT.parse(response.getResolved().getAccessToken())
                .getJWTClaimsSet().getExpirationTime().toInstant();

        assertThat(expiresAt).isBeforeOrEqualTo(opaqueTokenExpiry);
    }

    @Test
    void unknownResourceIsACallerError() {
        assertThatThrownBy(() -> service.exchange(OPAQUE_TOKEN, validProof(), "billing-service"))
                .isInstanceOf(UnknownResourceException.class);

        verifyNoInteractions(authorizationService);
    }

    @Test
    void unknownTokenIsMalformed() {
        assertInvalid(service.exchange("never-issued", validProof(), "ledger-service"), InvalidReason.MALFORMED);
    }

    @Test
    void blankTokenIsMalformedWithoutALookup() {
        assertInvalid(service.exchange(" ", validProof(), "ledger-service"), InvalidReason.MALFORMED);

        verifyNoInteractions(authorizationService);
    }

    @Test
    void expiredTokenIsRefused() {
        storeAuthorization(Instant.now().minusSeconds(1), false, Map.of("jkt", jkt));

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.EXPIRED);
    }

    @Test
    void revokedTokenIsRefused() {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), true, Map.of("jkt", jkt));

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.REVOKED);
    }

    @Test
    void tokenOfADisabledUserIsRefused() {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of("jkt", jkt));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(false)));

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.REVOKED);
    }

    @Test
    void tokenOfADeletedUserIsRefused() {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of("jkt", jkt));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.REVOKED);
    }

    @Test
    void tokenOfADeletedClientIsRefused() {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of("jkt", jkt));
        when(registeredClientRepository.findById(client.getId())).thenReturn(null);

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.REVOKED);
    }

    @Test
    void proofFromAnotherKeyIsRefused() throws Exception {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of("jkt", jkt));
        ECKey thiefKey = new ECKeyGenerator(Curve.P_256).generate();
        String proof = DpopProofs.signedBy(thiefKey).forAccessToken(OPAQUE_TOKEN).build();

        assertInvalid(service.exchange(OPAQUE_TOKEN, proof, "ledger-service"), InvalidReason.DPOP_MISMATCH);
    }

    @Test
    void tokenThatIsNotBoundToAKeyIsRefused() {
        storeAuthorization(Instant.now().plus(Duration.ofMinutes(5)), false, Map.of());

        assertInvalid(service.exchange(OPAQUE_TOKEN, validProof(), "ledger-service"), InvalidReason.DPOP_MISMATCH);
    }

    private static String validProof() {
        return DpopProofs.signedBy(dpopKey).forAccessToken(OPAQUE_TOKEN).build();
    }

    private void storeAuthorization(Instant expiresAt, boolean invalidated, Map<String, Object> confirmation) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.DPOP, OPAQUE_TOKEN, expiresAt.minus(Duration.ofMinutes(5)), expiresAt,
                Set.of("openid", "profile"));

        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(USERNAME)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(accessToken, metadata -> {
                    metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, Map.of("cnf", confirmation));
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, invalidated);
                })
                .build();

        when(authorizationService.findByToken(OPAQUE_TOKEN, OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);
    }

    private static UserEntity user(boolean enabled) {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setEnabled(enabled);
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);

        return user;
    }

    private static void assertInvalid(TokenExchangeResponse response, InvalidReason reason) {
        assertThat(response.getResultCase()).isEqualTo(TokenExchangeResponse.ResultCase.INVALID);
        assertThat(response.getInvalid().getReason()).isEqualTo(reason);
    }
}
