package org.example.authorizationservice.service;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.authorizationservice.core.config.InternalTokenProperties;
import org.example.authorizationservice.core.exception.UnknownResourceException;
import org.example.authorizationservice.model.UserEntity;
import org.example.authorizationservice.repository.UserRepository;
import org.example.grpc.auth.InvalidReason;
import org.example.grpc.auth.InvalidToken;
import org.example.grpc.auth.ResolvedToken;
import org.example.grpc.auth.TokenExchangeResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exchanges a client's opaque access token for a short-lived internal JWT (RFC 9068 profile) that exactly one internal
 * service accepts. This is the only place internal JWTs are minted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExchangeService {

    private static final String TOKEN_TYPE = "at+jwt";

    private final OAuth2AuthorizationService authorizationService;
    private final RegisteredClientRepository registeredClientRepository;
    private final UserRepository userRepository;
    private final DpopProofVerifier dpopProofVerifier;
    private final JwtEncoder jwtEncoder;
    private final AuthorizationServerSettings authorizationServerSettings;
    private final InternalTokenProperties properties;

    /**
     * @throws UnknownResourceException if the resource is not a configured internal audience
     */
    public TokenExchangeResponse exchange(String opaqueToken, String dpopProof, String resource) {
        if (!properties.audiences().contains(resource)) throw new UnknownResourceException(resource);
        if (opaqueToken.isBlank()) return invalid(InvalidReason.MALFORMED);

        OAuth2Authorization authorization = authorizationService.findByToken(opaqueToken, OAuth2TokenType.ACCESS_TOKEN);

        if (authorization == null || authorization.getAccessToken() == null) return invalid(InvalidReason.MALFORMED);

        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        Instant now = Instant.now();
        Instant opaqueTokenExpiry = accessToken.getToken().getExpiresAt();

        if (accessToken.isInvalidated()) return invalid(InvalidReason.REVOKED);
        if (opaqueTokenExpiry == null || !opaqueTokenExpiry.isAfter(now)) return invalid(InvalidReason.EXPIRED);

        String jkt = boundKeyThumbprint(accessToken);

        if (jkt == null || !dpopProofVerifier.isValid(dpopProof, opaqueToken, jkt))
            return invalid(InvalidReason.DPOP_MISMATCH);

        // The token outlives a deleted client or a disabled user in the authorization store, so both are re checked
        RegisteredClient client = registeredClientRepository.findById(authorization.getRegisteredClientId());
        UserEntity user = userRepository.findByUsername(authorization.getPrincipalName())
                .filter(TokenExchangeService::isActive)
                .orElse(null);

        if (client == null || user == null) return invalid(InvalidReason.REVOKED);

        // JWT timestamps are whole seconds; truncating keeps the returned expiry identical to the 'exp' claim
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = earlier(issuedAt.plus(properties.ttl()), opaqueTokenExpiry.truncatedTo(ChronoUnit.SECONDS));

        if (!expiresAt.isAfter(issuedAt)) return invalid(InvalidReason.EXPIRED);

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(authorizationServerSettings.getIssuer())
                .subject(user.getId().toString())
                .audience(List.of(resource))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("client_id", client.getClientId())
                .claim("cnf", Map.of("jkt", jkt));

        Set<String> scopes = accessToken.getToken().getScopes();
        if (!scopes.isEmpty()) claims.claim("scope", String.join(" ", scopes));

        // Encoded directly, not through the token generator; its JWT customizer refuses access tokens by design
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.PS256).type(TOKEN_TYPE).build();
        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();

        return TokenExchangeResponse.newBuilder()
                .setResolved(ResolvedToken.newBuilder()
                        .setAccessToken(jwt)
                        .setCnfJkt(jkt)
                        .setExpiresAt(Timestamp.newBuilder().setSeconds(expiresAt.getEpochSecond())))
                .build();
    }

    private static @Nullable String boundKeyThumbprint(OAuth2Authorization.Token<OAuth2AccessToken> accessToken) {
        Map<String, Object> claims = accessToken.getClaims();

        if (claims == null
                || !(claims.get("cnf") instanceof Map<?, ?> confirmation)
                || !(confirmation.get("jkt") instanceof String jkt)) {
            return null;
        }

        return jkt;
    }

    private static boolean isActive(UserEntity user) {
        return user.isEnabled() && user.isAccountNonLocked() && user.isAccountNonExpired()
                && user.isCredentialsNonExpired();
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static TokenExchangeResponse invalid(InvalidReason reason) {
        log.info("Token exchange refused: {}", reason);

        return TokenExchangeResponse.newBuilder()
                .setInvalid(InvalidToken.newBuilder().setReason(reason))
                .build();
    }
}
