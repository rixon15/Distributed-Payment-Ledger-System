package org.example.authorizationservice.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Set;

/**
 * Settings for the internal JWTs minted by ExchangeToken.
 * @param ttl lifetime of an internal token; it bounds how long a revoked client token keeps working, because the
 * gateway caches the internal token until it expires
 * @param audiences internal services a token can be minted for; each token names exactly one of them as its 'aud'
 */
@ConfigurationProperties(prefix = "internal-token")
public record InternalTokenProperties(
        @DefaultValue("60s") Duration ttl,
        Set<String> audiences
) {

    public InternalTokenProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("internal-token.ttl must be positive");
        }

        if (audiences == null || audiences.isEmpty() || audiences.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("internal-token.audiences must list at least one service name");
        }

        audiences = Set.copyOf(audiences);
    }

}
