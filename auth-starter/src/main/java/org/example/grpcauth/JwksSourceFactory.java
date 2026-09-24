package org.example.grpcauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * Builds the remote JWK Set source the verifier selects signing keys from.
 *
 * <p>Nimbus composes the source from decorators, outermost first: cache with scheduled refresh-ahead, rate limiter,
 * outage tolerance, one retry, HTTP retriever. As a result:
 * <ul>
 *     <li>nothing is fetched until the first token arrives, so a service can start before the authorization
 *     server;</li>
 *     <li>a token whose 'kid' isn't in the cached set triggers a refetch, which is how key rotation propagates, and
 *     the rate limiter keeps a stream of made-up 'kid's from becoming a stream of JWKS requests;</li>
 *     <li>while the endpoint is down, the last fetched set keeps being served for the outage tolerance.</li>
 * </ul>
 *
 * <p>The returned source is {@link java.io.Closeable} at runtime and owns non-daemon refresh threads, so whoever
 * creates it must close it.
 */
public final class JwksSourceFactory {

    private JwksSourceFactory(){}

    public static JWKSource<SecurityContext> create(URI jwksUri, GrpcJwtAuthProperties.Jwks jwks) {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                toMillis(jwks.connectTimeout()),
                toMillis(jwks.readTimeout()),
                JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT);

        return JWKSourceBuilder.<SecurityContext>create(toUrl(jwksUri), retriever)
                .cache(jwks.cacheTtl().toMillis(), jwks.refreshTimeout().toMillis())
                .refreshAheadCache(jwks.refreshAhead().toMillis(), true)
                .rateLimited(jwks.rateLimit().toMillis())
                .outageTolerant(jwks.outageTolerance().toMillis())
                .retrying(true)
                .build();
    }

    private static int toMillis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid JWK Set URI: " + uri, e);
        }
    }
}
