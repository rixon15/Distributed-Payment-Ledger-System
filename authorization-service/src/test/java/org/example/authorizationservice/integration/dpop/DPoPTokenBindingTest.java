package org.example.authorizationservice.integration.dpop;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DPoPTokenBindingTest extends AbstractIntegrationTest {

    private static final String TEST_CLIENT_ID = "dpop-test-client";
    private static final String REDIRECT_URI = "https://client.example.com/callback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    private HttpServer jwksServer;
    private RSAKey clientAssertionKey;
    private String parEndpoint;
    private String tokenEndpoint;

    @BeforeAll
    void setup() throws Exception {
        clientAssertionKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.PS256)
                .keyID(UUID.randomUUID().toString())
                .generate();

        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(clientAssertionKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
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
        tokenEndpoint = issuer + "/oauth2/token";
    }

    @AfterAll
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void authorizationCodeRedeemedWithDPoPProofYieldsSenderConstrainedToken() throws Exception {
        String codeVerifier = "test-code-verifier-0123456789-0123456789-0123456789-abcdef";
        String code = obtainAuthorizationCode(codeVerifier);

        RSAKey dPoPKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.PS256)
                .keyID(UUID.randomUUID().toString())
                .generate();

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", REDIRECT_URI);
        body.add("code_verifier", codeVerifier);
        body.add("client_id", TEST_CLIENT_ID);
        body.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        body.add("client_assertion", signedClientAssertion(tokenEndpoint));

        String tokenResponseJson = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("DPoP", dPoPProof(tokenEndpoint, "POST", dPoPKey))
                        .params(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String tokenType = JsonPath.read(tokenResponseJson, "$.token_type");
        String accessToken = JsonPath.read(tokenResponseJson, "$.access_token");

        assertThat(tokenType).isEqualToIgnoringCase("DPoP");

        SignedJWT parsedAccessToken = SignedJWT.parse(accessToken);
        Map<String, Object> cnf = (Map<String, Object>) parsedAccessToken.getJWTClaimsSet().getClaim("cnf");

        assertThat(cnf).isNotNull();
        assertThat(cnf.get("jkt")).isEqualTo(dPoPKey.computeThumbprint().toString());
    }

    private String dPoPProof(String htu, String htm, RSAKey dPoPKey) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("htm", htm)
                .claim("htu", htu)
                .issueTime(Date.from(Instant.now()))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.PS256)
                        .type(new JOSEObjectType("dpop+jwt"))
                        .jwk(dPoPKey.toPublicJWK())
                        .build(),
                claims);

        jwt.sign(new RSASSASigner(dPoPKey));

        return jwt.serialize();
    }

    private String obtainAuthorizationCode(String codeVerifier) throws Exception {
        MultiValueMap<String, String> parBody = new LinkedMultiValueMap<>();
        parBody.add("response_type", "code");
        parBody.add("client_id", TEST_CLIENT_ID);
        parBody.add("redirect_uri", REDIRECT_URI);
        parBody.add("code_challenge", codeChallenge(codeVerifier));
        parBody.add("code_challenge_method", "S256");
        parBody.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        parBody.add("client_assertion", signedClientAssertion(parEndpoint));

        String parResponseJson = mockMvc.perform(post("/oauth2/par")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(parBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String requestUri = JsonPath.read(parResponseJson, "$.request_uri");

        URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("client_id", TEST_CLIENT_ID)
                .queryParam("request_uri", requestUri)
                .build()
                .encode()
                .toUri();

        MvcResult authorizeResult = mockMvc.perform(get(authorizeUri)
                        .with(user("test-subject")))
                .andReturn();

        String redirectedUrl = authorizeResult.getResponse().getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull();

        return UriComponentsBuilder.fromUriString(redirectedUrl)
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String signedClientAssertion(String audience) throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TEST_CLIENT_ID)
                .subject(TEST_CLIENT_ID)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.PS256).keyID(clientAssertionKey.getKeyID()).build(), claims);
        signedJwt.sign(new RSASSASigner(clientAssertionKey));

        return signedJwt.serialize();
    }

}
