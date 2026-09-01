package org.example.gatewayservice.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

;

@Service
@RequiredArgsConstructor
public class TokenCacheService {

    private static final String KEY_PREFIX = "phantom-token:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<CachedToken> get(String opaqueToken) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + opaqueToken);
        if (json == null) return Optional.empty();

        try {
            return Optional.of(objectMapper.readValue(json, CachedToken.class));
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    public void put(String opaqueToken, CachedToken token) {
        Duration ttl = Duration.between(Instant.now(), token.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) return;

        try {
            String json = objectMapper.writeValueAsString(token);
            redisTemplate.opsForValue().set(KEY_PREFIX + opaqueToken, json, ttl);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }
}
