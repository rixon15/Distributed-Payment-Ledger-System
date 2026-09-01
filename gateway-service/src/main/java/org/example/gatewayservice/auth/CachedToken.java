package org.example.gatewayservice.auth;

import java.time.Instant;

public record CachedToken(
        String accessToken,
        String cnfJkt,
        Instant expiresAt
) {
}
