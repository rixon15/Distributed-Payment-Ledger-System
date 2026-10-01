package org.example.authorizationservice.integration.exchange;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.example.authorizationservice.grpc.AuthServiceGrpcImpl;
import org.example.authorizationservice.integration.base.AbstractIntegrationTest;
import org.example.authorizationservice.support.DpopProofs;
import org.example.authorizationservice.support.TestOAuthClient;
import org.example.grpc.auth.AuthServiceGrpc;
import org.example.grpc.auth.InvalidReason;
import org.example.grpc.auth.TokenExchangeRequest;
import org.example.grpc.auth.TokenExchangeResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exchange end to end: a real opaque token from the token endpoint, exchanged over gRPC, and the resulting JWT
 * verified the way an internal service would, against the published JWK Set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenExchangeIntegrationTest extends AbstractIntegrationTest {

    // Seeded by V4__seed_dev_users.sql
    private static final String USERNAME = "demo-user";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private AuthServiceGrpcImpl authService;

    private TestOAuthClient client;
    private ECKey dpopKey;
    private Server server;
    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub stub;

    @BeforeAll
    void setUp() throws Exception {
        client = new TestOAuthClient(mockMvc, registeredClientRepository, "exchange-test-client");
        dpopKey = new ECKeyGenerator(Curve.P_256).generate();

        String serverName = "in-process-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(authService).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = AuthServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    void tearDown() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        client.close();
    }

    @Test
    void exchangeTokenVerifiesAgainstThePublishedKeys() throws Exception {
        String opaqueToken = client.obtainAccessToken(USERNAME, dpopKey);

        TokenExchangeResponse response = exchange(opaqueToken, "ledger-service");

        assertThat(response.getResultCase()).isEqualTo(TokenExchangeResponse.ResultCase.RESOLVED);

        SignedJWT jwt = SignedJWT.parse(response.getResolved().getAccessToken());
        JWK publishedKey = publishedKeys().getKeyByKeyId(jwt.getHeader().getKeyID());

        assertThat(publishedKey).isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier(publishedKey.toRSAKey()))).isTrue();
        assertThat(jwt.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Instant expiresAt = claims.getExpirationTime().toInstant();

        assertThat(claims.getIssuer()).isEqualTo(client.issuer());
        assertThat(claims.getSubject()).isEqualTo(USER_ID);
        assertThat(claims.getAudience()).containsExactly("ledger-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("exchange-test-client");
        assertThat(claims.getStringClaim("scope")).isEqualTo("profile");
        assertThat(claims.getJSONObjectClaim("cnf")).containsEntry("jkt", dpopKey.computeThumbprint().toString());
        assertThat(Duration.between(claims.getIssueTime().toInstant(), expiresAt)).isEqualTo(Duration.ofSeconds(60));
        assertThat(response.getResolved().getExpiresAt().getSeconds()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    void sameTokenYieldsADifferentJwtPerAudience() throws Exception {
        String opaqueToken = client.obtainAccessToken(USERNAME, dpopKey);

        JWTClaimsSet forLedger = claimsOf(exchange(opaqueToken, "ledger-service"));
        JWTClaimsSet forPayment = claimsOf(exchange(opaqueToken, "payment-service"));

        assertThat(forLedger.getAudience()).containsExactly("ledger-service");
        assertThat(forPayment.getAudience()).containsExactly("payment-service");
        assertThat(forLedger.getJWTID()).isNotEqualTo(forPayment.getJWTID());
    }

    @Test
    void revokedTokenIsRefused() throws Exception {
        String opaqueToken = client.obtainAccessToken(USERNAME, dpopKey);
        client.revoke(opaqueToken);

        TokenExchangeResponse response = exchange(opaqueToken, "ledger-service");

        assertThat(response.getResultCase()).isEqualTo(TokenExchangeResponse.ResultCase.INVALID);
        assertThat(response.getInvalid().getReason()).isEqualTo(InvalidReason.REVOKED);
    }

    @Test
    void proofFromAnotherKeyIsRefused() throws Exception {
        String opaqueToken = client.obtainAccessToken(USERNAME, dpopKey);
        ECKey thiefKey = new ECKeyGenerator(Curve.P_256).generate();

        TokenExchangeResponse response = stub.exchangeToken(TokenExchangeRequest.newBuilder()
                .setOpaqueToken(opaqueToken)
                .setDpopProof(DpopProofs.signedBy(thiefKey).forAccessToken(opaqueToken).build())
                .setResource("ledger-service")
                .build());

        assertThat(response.getInvalid().getReason()).isEqualTo(InvalidReason.DPOP_MISMATCH);
    }

    @Test
    void unknownResourceFailsTheCall() throws Exception {
        String opaqueToken = client.obtainAccessToken(USERNAME, dpopKey);

        assertThatThrownBy(() -> exchange(opaqueToken, "billing-service"))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode())
                                .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    private TokenExchangeResponse exchange(String opaqueToken, String resource) {
        return stub.exchangeToken(TokenExchangeRequest.newBuilder()
                .setOpaqueToken(opaqueToken)
                .setDpopProof(DpopProofs.signedBy(dpopKey).forAccessToken(opaqueToken).build())
                .setResource(resource)
                .build());
    }

    private static JWTClaimsSet claimsOf(TokenExchangeResponse response) throws Exception {
        return SignedJWT.parse(response.getResolved().getAccessToken()).getJWTClaimsSet();
    }

    private JWKSet publishedKeys() throws Exception {
        String jwks = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JWKSet.parse(jwks);
    }
}
