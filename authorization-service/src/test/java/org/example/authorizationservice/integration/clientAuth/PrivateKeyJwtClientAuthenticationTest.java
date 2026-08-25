package org.example.authorizationservice.integration.clientAuth;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.example.authorizationservice.integration.base.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivateKeyJwtClientAuthenticationTest extends AbstractIntegrationTest {

    private static final String TEST_CLIENT_ID = "private-key-jwt-test-client";
    private static final String REDIRECT_URI = "https://client.example.com/callback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;


    private HttpServer jwksServer;
    private RSAKey clientRsaKey;
    private String parEndpoint;

    @BeforeAll
    void setup() throws Exception {
        clientRsaKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.PS256)
                .keyID(UUID.randomUUID().toString())
                .generate();

        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(clientRsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        jwksServer.start();

        if (registeredClientRepository.findByClientId(TEST_CLIENT_ID) == null) {
            RegisteredClient testClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(TEST_CLIENT_ID)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(REDIRECT_URI)
                    .scope("openid")
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(false)
                            .jwkSetUrl("http://localhost:" + jwksServer.getAddress().getPort() + "/jwks")
                            .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.PS256)
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                            .build())
                    .build();

            registeredClientRepository.save(testClient);
        }

        String metadataJson = mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String issuer = JsonPath.read(metadataJson, "$.issuer");
        parEndpoint = issuer + "/oauth2/par";
    }

    @AfterAll
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void perRequestWithValidClientAssertionIsAuthenticated() throws Exception {
        MultiValueMap<String, String> body = parRequestBody();
        body.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        body.add("client_assertion", signedClientAssertion());

        mockMvc.perform(post("/oauth2/par")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(body))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("request_uri")));
    }

    @Test
    void parRequestWithoutClientAssertionIsRejected() throws Exception {
        MultiValueMap<String, String> body = parRequestBody();

        mockMvc.perform(post("/oauth2/par")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(body))
                .andExpect(status().isBadRequest());
    }

    private String signedClientAssertion() throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TEST_CLIENT_ID)
                .subject(TEST_CLIENT_ID)
                .audience(parEndpoint)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.PS256).keyID(clientRsaKey.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(clientRsaKey));

        return signedJwt.serialize();
    }

    private MultiValueMap<String, String> parRequestBody() {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("response_type", "code");
        body.add("client_id", TEST_CLIENT_ID);
        body.add("redirect_uri", REDIRECT_URI);
        body.add("scope", "openid");
        body.add("code_challenge", codeChallenge());
        body.add("code_challenge_method", "S256");

        return body;
    }

    private String codeChallenge() {
        try {
            String verifier = "test-code-verifier-0123456789-0123456789-0123456789-abcdef";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
