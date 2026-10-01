package org.example.authorizationservice.support;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A registered OAuth client for integration tests: it serves its own JWK Set for private_key_jwt and drives the
 * PAR, authorize and token steps through MockMvc.
 */
public final class TestOAuthClient implements AutoCloseable {

    private static final String REDIRECT_URI = "https://client.example.com/callback";
    private static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final MockMvc mockMvc;
    private final String clientId;
    private final RSAKey clientAssertionKey;
    private final HttpServer jwksServer;
    private final String issuer;

    public TestOAuthClient(MockMvc mockMvc, RegisteredClientRepository registeredClientRepository, String clientId)
            throws Exception {
        this.mockMvc = mockMvc;
        this.clientId = clientId;
        this.clientAssertionKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        this.jwksServer = startJwksServer();

        // Replaced on every run: the JWK Set URL changes with the port, and Spring test contexts share one database
        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);

        registeredClientRepository.save(RegisteredClient
                .withId(existing != null ? existing.getId() : UUID.randomUUID().toString())
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("profile")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .jwkSetUrl("http://localhost:" + jwksServer.getAddress().getPort() + "/jwks")
                        .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.PS256)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                        .build())
                .build());

        String metadata = mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        this.issuer = JsonPath.read(metadata, "$.issuer");
    }

    public String issuer() {
        return issuer;
    }

    public String obtainAccessToken(String username, JWK dpopKey) throws Exception {
        String codeVerifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String tokenEndpoint = issuer + "/oauth2/token";

        MultiValueMap<String, String> parBody = new LinkedMultiValueMap<>();
        parBody.add("response_type", "code");
        parBody.add("client_id", clientId);
        parBody.add("redirect_uri", REDIRECT_URI);
        parBody.add("scope", "profile");
        parBody.add("code_challenge", codeChallenge(codeVerifier));
        parBody.add("code_challenge_method", "S256");
        parBody.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        parBody.add("client_assertion", clientAssertion(issuer + "/oauth2/par"));

        String parResponse = mockMvc.perform(post("/oauth2/par")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(parBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String redirectedUrl = mockMvc.perform(get(UriComponentsBuilder.fromPath("/oauth2/authorize")
                        .queryParam("client_id", clientId)
                        .queryParam("request_uri", JsonPath.<String>read(parResponse, "$.request_uri"))
                        .build().encode().toUri())
                        .with(user(username)))
                .andReturn().getResponse().getRedirectedUrl();

        assert redirectedUrl != null;
        String code = UriComponentsBuilder.fromUriString(redirectedUrl).build().getQueryParams().getFirst("code");

        MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
        tokenBody.add("grant_type", "authorization_code");
        tokenBody.add("code", code);
        tokenBody.add("redirect_uri", REDIRECT_URI);
        tokenBody.add("code_verifier", codeVerifier);
        tokenBody.add("client_id", clientId);
        tokenBody.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        tokenBody.add("client_assertion", clientAssertion(tokenEndpoint));

        String tokenResponse = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("DPoP", DpopProofs.signedBy(dpopKey).forRequest("POST", tokenEndpoint).build())
                        .params(tokenBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(tokenResponse, "$.access_token");
    }

    public void revoke(String accessToken) throws Exception {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", accessToken);
        body.add("token_type_hint", "access_token");
        body.add("client_id", clientId);
        body.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        body.add("client_assertion", clientAssertion(issuer + "/oauth2/revoke"));

        mockMvc.perform(post("/oauth2/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(body))
                .andExpect(status().isOk());
    }

    @Override
    public void close() {
        jwksServer.stop(0);
    }

    private String clientAssertion(String audience) throws Exception {
        Instant now = Instant.now();

        SignedJWT assertion = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.PS256).keyID(clientAssertionKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(clientId)
                        .subject(clientId)
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(60)))
                        .jwtID(UUID.randomUUID().toString())
                        .build());

        assertion.sign(new RSASSASigner(clientAssertionKey));

        return assertion.serialize();
    }

    private HttpServer startJwksServer() throws Exception {
        byte[] body = new JWKSet(clientAssertionKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        return server;
    }

    private static String codeChallenge(String codeVerifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));

        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
