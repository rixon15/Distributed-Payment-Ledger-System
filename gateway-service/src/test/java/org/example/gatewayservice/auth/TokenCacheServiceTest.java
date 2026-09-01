package org.example.gatewayservice.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TokenCacheServiceTest {

    private ValueOperations<String, String> valueOperations;
    private TokenCacheService tokenCacheService;

    @BeforeEach
    void setUp() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ObjectMapper objectMapper = new ObjectMapper();

        tokenCacheService = new TokenCacheService(redisTemplate, objectMapper);
    }

    @Test
    void get_returnsEmpty_onCacheMiss() {
        when(valueOperations.get("phantom-token:opaque-1")).thenReturn(null);

        assertThat(tokenCacheService.get("opaque-1")).isEmpty();
    }

    @Test
    void get_returnsDeserializedToken_onCacheHit() {
        when(valueOperations.get("phantom-token:opaque-1")).thenReturn(
                "{\"accessToken\":\"jwt-value\",\"cnfJkt\":\"jkt-thumbprint\",\"expiresAt\":\"2026-09-01T12:00:00Z\"}"
        );

        Optional<CachedToken> result = tokenCacheService.get("opaque-1");

        assertThat(result).contains(new CachedToken("jwt-value", "jkt-thumbprint",
                Instant.parse("2026-09-01T12:00:00Z")));
    }

    @Test
    void put_storesWithTtlPinnedToExpiry() {
        Instant expiresAt = Instant.now().plus(45, ChronoUnit.SECONDS);
        CachedToken token = new CachedToken("jwt-value", "jkt-thumbprint", expiresAt);

        tokenCacheService.put("opaque-1", token);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("phantom-token:opaque-1"), any(String.class), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue().toSeconds()).isBetween(43L, 45L);
    }

    @Test
    void put_skipsCaching_whenAlreadyExpired() {
        CachedToken token = new CachedToken("jwt-value", "jkt-thumbprint", Instant.now().minusSeconds(5));

        tokenCacheService.put("opaque-1", token);

        verifyNoInteractions(valueOperations);
    }
}
