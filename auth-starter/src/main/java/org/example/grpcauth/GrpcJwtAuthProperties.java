package org.example.grpcauth;

import com.nimbusds.jose.JWSAlgorithm;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Configuration for verifying internal JWTs on incoming gRPC calls.
 *
 * <p>Required values are checked in the constructor rather than through Bean Validation: {@code @Validated} is only
 * applied when a validator implementation happens to be on the consuming service's classpath, and a silently skipped
 * check would leave the service without an issuer or audience to verify tokens against.
 *
 * @param enabled       whether incoming gRPC calls are authenticated at all
 * @param issuer        expected 'iss' claim, compared exactly
 * @param audience      identifier of this service, which must appear in the token's 'aud' claim
 * @param jwksUri       JWK Set endpoint of the authorization server
 * @param algorithms    accepted JWS algorithms; only asymmetric signature algorithms are allowed
 * @param tokenType     required 'typ' header value ('at+jwt' per RFC 9068)
 * @param clockSkew     tolerance applied to the 'exp', 'nbf' and 'iat' checks
 * @param maxTokenAge   maximum token age based on its 'iat' claim, enforced regardless of 'exp'
 * @param publicMethods methods callable without a token, as 'package.Service/*' or 'package.Service/Method'
 * @param jwks          JWK Set retrieval and caching settings
 */
@ConfigurationProperties(prefix = "grpc.auth")
public record GrpcJwtAuthProperties(
        @DefaultValue("true") boolean enabled,
        String issuer,
        String audience,
        URI jwksUri,
        @DefaultValue("PS256") List<String> algorithms,
        @DefaultValue("at+jwt") String tokenType,
        @DefaultValue("30s") Duration clockSkew,
        @DefaultValue("5m") Duration maxTokenAge,
        @DefaultValue("grpc.health.v1.Health/*") List<String> publicMethods,
        @DefaultValue Jwks jwks
) {

    private static final String PREFIX = "grpc.auth.";
    private static final Pattern METHOD_PATTERN = Pattern.compile("[^/\\s]+/([^/\\s*]+|\\*)");

    public GrpcJwtAuthProperties {
        algorithms = List.copyOf(algorithms);
        publicMethods = List.copyOf(publicMethods);

        if (enabled) {
            requireText(issuer, "issuer");
            requireText(audience, "audience");
            requireHttpUri(jwksUri, "jwks-uri");
            requireSignatureAlgorithms(algorithms);
            requireText(tokenType, "token-type");
            requireNonNegative(clockSkew, "clock-skew");
            requirePositive(maxTokenAge, "max-token-age");

            if (maxTokenAge.compareTo(clockSkew) <= 0)
                throw new IllegalArgumentException(PREFIX + "max-token-age must be greater than "
                        + PREFIX + "clock-skew");

            for (String method : publicMethods) {
                if (!METHOD_PATTERN.matcher(method).matches()) {
                    throw new IllegalArgumentException(PREFIX + "public-methods entry '" + method
                            + "' must be 'package.Service/*' or 'package.Service/Method'");
                }

            }

            if (jwks == null) throw new IllegalArgumentException(PREFIX + "jwks must be set");
        }
    }

    /**
     * @return the configured algorithms as Nimbus types, for building the JWS key selector
     */
    public Set<JWSAlgorithm> jwsAlgorithms() {
        return algorithms.stream()
                .map(JWSAlgorithm::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @param cacheTtl        how long a fetched JWK Set is served from cache
     * @param refreshAhead    how long before cache expiry a background refresh starts; together with the refresh
     *                        timeout (see refreshTimeout()) it must fit within cache-ttl
     * @param outageTolerance how long the last fetched JWK Set keeps being used while the endpoint is unreachable
     * @param rateLimit       window opened by a fetch in which at most one further fetch is allowed, including
     *                        fetches triggered by an unknown 'kid'; must be shorter than cache-ttl
     * @param connectTimeout  connect timeout for the JWK Set endpoint
     * @param readTimeout     read timeout for the JWK Set endpoint
     */
    public record Jwks(
            @DefaultValue("5m") Duration cacheTtl,
            @DefaultValue("30s") Duration refreshAhead,
            @DefaultValue("1h") Duration outageTolerance,
            @DefaultValue("30s") Duration rateLimit,
            @DefaultValue("500ms") Duration connectTimeout,
            @DefaultValue("1s") Duration readTimeout
    ) {
        private static final String JWKS_PREFIX = "jwks.";

        public Jwks {
            requirePositive(cacheTtl, JWKS_PREFIX + "cache-ttl");
            requirePositive(refreshAhead, JWKS_PREFIX + "refresh-ahead");
            requirePositive(outageTolerance, JWKS_PREFIX + "outage-tolerance");
            requirePositive(rateLimit, JWKS_PREFIX + "rate-limit");
            requirePositive(connectTimeout, JWKS_PREFIX + "connect-timeout");
            requirePositive(readTimeout, JWKS_PREFIX + "read-timeout");

            // Nimbus rejects both combinations at build time; checking here names the properties to fix
            if (refreshAhead.plus(refreshTimeout(connectTimeout, readTimeout)).compareTo(cacheTtl) > 0) {
                throw new IllegalArgumentException(PREFIX + "jwks.refresh-ahead plus twice (" + PREFIX
                        + "jwks.connect-timeout + " + PREFIX + "jwks.read-timeout) must not exceed " + PREFIX +
                        "jwks.cache-ttl");
            }

            if (rateLimit.compareTo(cacheTtl) >= 0) {
                throw new IllegalArgumentException(PREFIX + "jwks.rate-limit must be shorter than " + PREFIX
                        + "jwks.cache-ttl");
            }
        }

        /**
         * @return how long a request waits for a JWK Set refresh already in progress: one fetch plus its one retry
         */
        public Duration refreshTimeout() {
            return refreshTimeout(connectTimeout, readTimeout);
        }

        private static Duration refreshTimeout(Duration connectTimeout, Duration readTimeout) {
            return connectTimeout.plus(readTimeout).multipliedBy(2);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(PREFIX + name + " must be set");
    }

    private static void requireHttpUri(URI value, String name) {
        if (value == null)
            throw new IllegalArgumentException(PREFIX + name + " must be set");

        String scheme = value.getScheme();
        if (!value.isAbsolute() || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException(PREFIX + name + " must be an absolute http(s) URI");
    }

    private static void requireSignatureAlgorithms(List<String> algorithms) {
        if (algorithms.isEmpty())
            throw new IllegalArgumentException(PREFIX + "algorithms must not be empty");

        for (String name : algorithms) {
            // Family.SIGNATURE holds only asymmetric algorithms: HMAC and "none" can never be configured in
            if (!JWSAlgorithm.Family.SIGNATURE.contains(JWSAlgorithm.parse(name)))
                throw new IllegalArgumentException(PREFIX + "algorithms entry '" + name
                        + "' is not an asymmetric JWS signature algorithm");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative())
            throw new IllegalArgumentException(PREFIX + name + " must be zero or positive");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero())
            throw new IllegalArgumentException(PREFIX + name + " must be positive");
    }
}
