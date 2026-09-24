package org.example.grpcauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import org.example.grpcauth.exception.InvalidTokenException;
import org.example.grpcauth.exception.JwksUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grpcauth.TestTokens.claims;
import static org.example.grpcauth.TestTokens.sign;

/**
 * Runs the Nimbus source built by {@link JwksSourceFactory} against a real HTTP endpoint, to check the decorators
 * are wired with our settings rather than retesting Nimbus itself.
 */
class JwksSourceIntegrationTest {

    // Short enough to cross cache expiry in a test; the refresh timeout is 2 * (100ms + 200ms)
    private static final Map<String, String> FAST_JWKS = Map.of(
            "grpc.auth.jwks.cache-ttl", "2s",
            "grpc.auth.jwks.refresh-ahead", "200ms",
            "grpc.auth.jwks.rate-limit", "1s",
            "grpc.auth.jwks.connect-timeout", "100ms",
            "grpc.auth.jwks.read-timeout", "200ms");

    private static final RSAKey KEY_1 = TestTokens.rsaKey("key-1");
    private static final RSAKey KEY_2 = TestTokens.rsaKey("key-2");

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JWKSet> served = new AtomicReference<>(new JWKSet(KEY_1.toPublicJWK()));
    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicBoolean slow = new AtomicBoolean();

    private HttpServer server;
    private JWKSource<SecurityContext> source;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/oauth2/jwks", exchange -> {
            requests.incrementAndGet();
            if (slow.get()) sleep(1000);
            if (failing.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = served.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        if (source instanceof Closeable closeable) closeable.close();
        server.stop(0);
    }

    private JwtTokenVerifier verifierFor(URI jwksUri) {
        GrpcJwtAuthProperties properties = TestTokens.properties(FAST_JWKS);
        source = JwksSourceFactory.create(jwksUri, properties.jwks());
        return new JwtTokenVerifier(properties, source);
    }

    private JwtTokenVerifier verifier() {
        return verifierFor(URI.create("http://localhost:" + server.getAddress().getPort() + "/oauth2/jwks"));
    }

    private static String tokenSignedBy(RSAKey key) {
        return sign(key, claims(Instant.now()).build());
    }

    @Test
    void fetchesLazilyOnFirstTokenThenServesFromCache() throws Exception {
        JwtTokenVerifier verifier = verifier();
        assertThat(requests).hasValue(0);

        verifier.verify(tokenSignedBy(KEY_1));
        verifier.verify(tokenSignedBy(KEY_1));

        assertThat(requests).hasValue(1);
    }

    @Test
    void refetchesOnUnknownKeyIdSoRotationPropagates() throws Exception {
        JwtTokenVerifier verifier = verifier();
        verifier.verify(tokenSignedBy(KEY_1));

        served.set(new JWKSet(List.of(KEY_1.toPublicJWK(), KEY_2.toPublicJWK())));

        assertThat(verifier.verify(tokenSignedBy(KEY_2)).subject()).isEqualTo(TestTokens.SUBJECT);
        assertThat(requests).hasValue(2);
    }

    @Test
    void rateLimitsRefetchesTriggeredByUnknownKeyIds() throws Exception {
        JwtTokenVerifier verifier = verifier();
        verifier.verify(tokenSignedBy(KEY_1));

        for (int i = 0; i < 5; i++) {
            String forged = tokenSignedBy(TestTokens.rsaKey("forged-" + i));
            assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(InvalidTokenException.class);
        }

        // The first fetch opens a window that allows one more: the first forged token's refetch
        assertThat(requests).hasValue(2);
    }

    @Test
    void keepsServingCachedKeysThroughAnOutage() throws Exception {
        JwtTokenVerifier verifier = verifier();
        verifier.verify(tokenSignedBy(KEY_1));

        failing.set(true);
        sleep(2500);

        assertThat(verifier.verify(tokenSignedBy(KEY_1)).subject()).isEqualTo(TestTokens.SUBJECT);
        assertThat(requests.get()).isGreaterThan(1);
    }

    @Test
    void reportsUnavailableWhenEndpointFailsAndNothingIsCached() {
        failing.set(true);
        JwtTokenVerifier verifier = verifier();

        assertThatThrownBy(() -> verifier.verify(tokenSignedBy(KEY_1))).isInstanceOf(JwksUnavailableException.class);
        assertThat(requests).as("one fetch plus one retry").hasValue(2);
    }

    @Test
    void reportsUnavailableWhenNothingListens() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        JwtTokenVerifier verifier = verifierFor(URI.create("http://localhost:" + closedPort + "/oauth2/jwks"));

        assertThatThrownBy(() -> verifier.verify(tokenSignedBy(KEY_1))).isInstanceOf(JwksUnavailableException.class);
    }

    @Test
    void appliesReadTimeout() {
        slow.set(true);
        JwtTokenVerifier verifier = verifier();

        long start = System.nanoTime();
        assertThatThrownBy(() -> verifier.verify(tokenSignedBy(KEY_1))).isInstanceOf(JwksUnavailableException.class);

        // Two attempts at a 200ms read timeout, well under the 1s the endpoint takes to answer
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(900);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
