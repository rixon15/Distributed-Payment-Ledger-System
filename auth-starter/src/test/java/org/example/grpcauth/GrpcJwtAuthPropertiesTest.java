package org.example.grpcauth;

import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds through Spring's {@link Binder} rather than calling the constructor, so the {@code @DefaultValue}s and
 * relaxed property names are covered by the same tests as the constructor checks.
 */
class GrpcJwtAuthPropertiesTest {

    private static Map<String, String> required() {
        Map<String, String> props = new HashMap<>();
        props.put("grpc.auth.issuer", "http://localhost:9000");
        props.put("grpc.auth.audience", "ledger-service");
        props.put("grpc.auth.jwks-uri", "http://localhost:9000/oauth2/jwks");
        return props;
    }

    private static Map<String, String> requiredWith(String key, String value) {
        Map<String, String> props = required();
        props.put(key, value);
        return props;
    }

    private static GrpcJwtAuthProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("grpc.auth", GrpcJwtAuthProperties.class);
    }

    private static void assertBindFails(Map<String, String> props, String message) {
        assertThatThrownBy(() -> bind(props))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    void appliesDefaultsWhenOnlyRequiredValuesAreSet() {
        GrpcJwtAuthProperties props = bind(required());

        assertThat(props.enabled()).isTrue();
        assertThat(props.issuer()).isEqualTo("http://localhost:9000");
        assertThat(props.audience()).isEqualTo("ledger-service");
        assertThat(props.jwksUri()).isEqualTo(URI.create("http://localhost:9000/oauth2/jwks"));
        assertThat(props.algorithms()).containsExactly("PS256");
        assertThat(props.tokenType()).isEqualTo("at+jwt");
        assertThat(props.clockSkew()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.maxTokenAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.publicMethods()).containsExactly("grpc.health.v1.Health/*");

        GrpcJwtAuthProperties.Jwks jwks = props.jwks();
        assertThat(jwks.cacheTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(jwks.refreshAhead()).isEqualTo(Duration.ofSeconds(30));
        assertThat(jwks.outageTolerance()).isEqualTo(Duration.ofHours(1));
        assertThat(jwks.rateLimit()).isEqualTo(Duration.ofSeconds(30));
        assertThat(jwks.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(jwks.readTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void bindsCustomValues() {
        Map<String, String> props = required();
        props.put("grpc.auth.algorithms", "RS256,ES256");
        props.put("grpc.auth.token-type", "jwt");
        props.put("grpc.auth.clock-skew", "0s");
        props.put("grpc.auth.max-token-age", "2m");
        props.put("grpc.auth.public-methods", "grpc.health.v1.Health/Check,ledger.v1.Ledger/*");
        props.put("grpc.auth.jwks.cache-ttl", "10m");
        props.put("grpc.auth.jwks.refresh-ahead", "1m");
        props.put("grpc.auth.jwks.read-timeout", "2s");

        GrpcJwtAuthProperties bound = bind(props);

        assertThat(bound.algorithms()).containsExactly("RS256", "ES256");
        assertThat(bound.tokenType()).isEqualTo("jwt");
        assertThat(bound.clockSkew()).isZero();
        assertThat(bound.maxTokenAge()).isEqualTo(Duration.ofMinutes(2));
        assertThat(bound.publicMethods()).containsExactly("grpc.health.v1.Health/Check", "ledger.v1.Ledger/*");
        assertThat(bound.jwks().cacheTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(bound.jwks().refreshAhead()).isEqualTo(Duration.ofMinutes(1));
        assertThat(bound.jwks().readTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(bound.jwks().rateLimit()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void failsFastWhenNothingIsConfigured() {
        assertBindFails(Map.of(), "grpc.auth.issuer must be set");
    }

    @Test
    void skipsRequiredChecksWhenDisabled() {
        GrpcJwtAuthProperties props = bind(Map.of("grpc.auth.enabled", "false"));

        assertThat(props.enabled()).isFalse();
        assertThat(props.issuer()).isNull();
        assertThat(props.audience()).isNull();
        assertThat(props.jwksUri()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience"})
    void rejectsBlankRequiredText(String name) {
        assertBindFails(requiredWith("grpc.auth." + name, "  "), "grpc.auth." + name + " must be set");
    }

    @Test
    void rejectsMissingAudience() {
        Map<String, String> props = required();
        props.remove("grpc.auth.audience");

        assertBindFails(props, "grpc.auth.audience must be set");
    }

    @Test
    void rejectsMissingJwksUri() {
        Map<String, String> props = required();
        props.remove("grpc.auth.audience");

        assertBindFails(props, "grpc.auth.audience must be set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/oauth2/jwks", "file:///etc/jwks.json", "ftp://localhost/jwks"})
    void rejectsJwksUriThatIsNotAbsoluteHttp(String uri) {
        assertBindFails(requiredWith("grpc.auth.jwks-uri", uri), "must be an absolute http(s) URI");
    }

    @Test
    void acceptsHttpsJwksUri() {
        GrpcJwtAuthProperties props = bind(requiredWith("grpc.auth.jwks-uri", "https://auth.internal/oauth2/jwks"));

        assertThat(props.jwksUri().getScheme()).isEqualTo("https");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS256", "none", "PS257"})
    void rejectsSymmetricNoneAndUnknownAlgorithms(String algorithm) {
        assertBindFails(requiredWith("grpc.auth.algorithms", algorithm),
                "algorithms entry '" + algorithm + "' is not an asymmetric JWS signature algorithm");
    }

    @Test
    void rejectsEmptyAlgorithmList() {
        assertBindFails(requiredWith("grpc.auth.algorithms", ""), "grpc.auth.algorithms must not be empty");
    }

    @Test
    void exposesAlgorithmsAsNimbusTypes() {
        GrpcJwtAuthProperties props = bind(requiredWith("grpc.auth.algorithms", "PS256,ES256"));

        assertThat(props.jwsAlgorithms()).containsExactlyInAnyOrder(JWSAlgorithm.PS256, JWSAlgorithm.ES256);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grpc.health.v1.Health", "/Check", "Health/", "a/b/c", "Health/Ch*", "my service/Check"})
    void rejectsMalformedPublicMethods(String method) {
        assertBindFails(requiredWith("grpc.auth.public-methods", method),
                "public-methods entry '" + method + "' must be");
    }

    @Test
    void acceptsEmptyPublicMethods() {
        GrpcJwtAuthProperties props = bind(requiredWith("grpc.auth.public-methods", ""));

        assertThat(props.publicMethods()).isEmpty();
    }

    @Test
    void rejectsNegativeClockSkew() {
        assertBindFails(requiredWith("grpc.auth.clock-skew", "-1s"), "grpc.auth.clock-skew must be zero or positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1m"})
    void rejectsNonPositiveMaxTokenAge(String value) {
        assertBindFails(requiredWith("grpc.auth.max-token-age", value), "grpc.auth.max-token-age must be positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"30s", "10s"})
    void rejectsMaxTokeAgeNotAboveClockSkew(String maxTokenAge) {
        assertBindFails(requiredWith("grpc.auth.max-token-age", maxTokenAge),
                "grpc.auth.max-token-age must be greater than grpc.auth.clock-skew");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cache-ttl", "refresh-ahead", "outage-tolerance", "rate-limit", "connect-timeout",
            "read-timeout"})
    void rejectsNonPositiveJwksDurations(String name) {
        assertBindFails(requiredWith("grpc.auth.jwks." + name, "0s"), "jwks." + name + " must be positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5m", "10m"})
    void rejectsRefreshAheadNotShorterThanCacheTtl(String refreshAhead) {
        assertBindFails(requiredWith("grpc.auth.jwks.refresh-ahead", refreshAhead),
                "grpc.auth.jwks.refresh-ahead must be shorter than grpc.auth.jwks.cache-ttl");
    }

    @Test
    void listsAreImmutableCopies() {
        List<String> algorithms = new java.util.ArrayList<>(List.of("PS256"));
        List<String> publicMethods = new java.util.ArrayList<>(List.of("grpc.health.v1.Health/*"));
        GrpcJwtAuthProperties props = new GrpcJwtAuthProperties(true, "http://localhost:9000", "ledger-service",
                URI.create("http://localhost:9000/oauth2/jwks"), algorithms, "at+jwt", Duration.ofSeconds(30),
                Duration.ofMinutes(5), publicMethods, bind(required()).jwks());

        algorithms.add("RS256");
        publicMethods.clear();

        assertThat(props.algorithms()).containsExactly("PS256");
        assertThat(props.publicMethods()).containsExactly("grpc.health.v1.Health/*");
        assertThatThrownBy(() -> props.algorithms().add("RS256")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(props.jwsAlgorithms()).isEqualTo(Set.of(JWSAlgorithm.PS256));
    }

    @Test
    void rejectsNullJwksWhenEnabled() {
        assertThatThrownBy(() -> new GrpcJwtAuthProperties(true, "http://localhost:9000", "ledger-service",
                URI.create("http://localhost:9000/oauth2/jwks"), List.of("PS256"), "at+jwt", Duration.ofSeconds(30),
                Duration.ofMinutes(5), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grpc.auth.jwks must be set");
    }
}
