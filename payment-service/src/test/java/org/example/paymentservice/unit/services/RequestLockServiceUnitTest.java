package org.example.paymentservice.unit.services;

import org.example.paymentservice.service.implementation.RequestLockServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RequestLockServiceUnitTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RequestLockServiceImp requestLockService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(requestLockService, "ttlMinutes", 5);
        requestLockService.init();
    }

    @Test
    void init_shouldSetLockTimeoutFromConfiguredMinutes() {
        Duration lockTimeout = (Duration) ReflectionTestUtils.getField(requestLockService, "lockTimeout");

        assertThat(lockTimeout).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void init_shouldOverwritePreviousLockTimeoutWhenCalledAgain() {
        ReflectionTestUtils.setField(requestLockService, "ttlMinutes", 10);

        requestLockService.init();

        Duration lockTimeout = (Duration) ReflectionTestUtils.getField(requestLockService, "lockTimeout");
        assertThat(lockTimeout).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void acquire_shouldReturnTrueWhenRedisReturnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        boolean result = requestLockService.acquire("request_lock:test-key");

        String ownerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");
        Duration lockTimeout = (Duration) ReflectionTestUtils.getField(requestLockService, "lockTimeout");

        assertThat(result).isTrue();
        assertThat(ownerId).isNotNull();
        assertThat(lockTimeout).isNotNull();
        verify(redisTemplate).opsForValue();
        verify(valueOperations).setIfAbsent("request_lock:test-key", ownerId, lockTimeout);
    }

    @Test
    void acquire_shouldReturnFalseWhenRedisReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);

        boolean result = requestLockService.acquire("request_lock:test-key");

        String ownerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");
        Duration lockTimeout = (Duration) ReflectionTestUtils.getField(requestLockService, "lockTimeout");

        assertThat(result).isFalse();
        assertThat(ownerId).isNotNull();
        assertThat(lockTimeout).isNotNull();
        verify(redisTemplate).opsForValue();
        verify(valueOperations).setIfAbsent("request_lock:test-key", ownerId, lockTimeout);
    }

    @Test
    void acquire_shouldReturnFalseWhenRedisReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);

        boolean result = requestLockService.acquire("request_lock:test-key");

        String ownerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");
        Duration lockTimeout = (Duration) ReflectionTestUtils.getField(requestLockService, "lockTimeout");

        assertThat(result).isFalse();
        verify(redisTemplate).opsForValue();
        assertThat(ownerId).isNotNull();
        assertThat(lockTimeout).isNotNull();
        verify(valueOperations).setIfAbsent("request_lock:test-key", ownerId, lockTimeout);
    }

    @Test
    void acquire_shouldUseConfiguredLockTimeout() {
        ReflectionTestUtils.setField(requestLockService, "ttlMinutes", 12);
        requestLockService.init();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        requestLockService.acquire("request_lock:timeout-check");

        String ownerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");

        assertThat(ownerId).isNotNull();
        verify(valueOperations).setIfAbsent(
                "request_lock:timeout-check",
                ownerId,
                Duration.ofMinutes(12)
        );
    }

    @Test
    void acquire_shouldReuseSameOwnerIdAcrossMultipleCalls() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        requestLockService.acquire("request_lock:key-1");
        requestLockService.acquire("request_lock:key-2");

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);

        verify(valueOperations, times(2))
                .setIfAbsent(anyString(), ownerCaptor.capture(), any(Duration.class));

        List<String> allOwnerIds = ownerCaptor.getAllValues();

        assertThat(allOwnerIds).hasSize(2);
        assertThat(allOwnerIds.get(0)).isEqualTo(allOwnerIds.get(1));
        assertThat(allOwnerIds.get(0)).isEqualTo(ReflectionTestUtils.getField(requestLockService, "ownerId"));
    }

    @Test
    void release_shouldExecuteStaticLuaScriptWithExactKeyAndOwnerId() {
        String key = "request_lock:test-key";

        requestLockService.release(key);

        
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);

        
        ArgumentCaptor<List<String>> keysCaptor =
                ArgumentCaptor.forClass(List.class);

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                keysCaptor.capture(),
                ownerCaptor.capture()
        );

        DefaultRedisScript<Long> capturedScript = scriptCaptor.getValue();
        List<String> capturedKeys = keysCaptor.getValue();
        String capturedOwnerId = ownerCaptor.getValue();

        String actualOwnerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");

        
        DefaultRedisScript<Long> staticScript =
                (DefaultRedisScript<Long>) ReflectionTestUtils.getField(RequestLockServiceImp.class, "SCRIPT");

        assertThat(capturedScript).isSameAs(staticScript);
        assertThat(capturedScript.getResultType()).isEqualTo(Long.class);
        assertThat(capturedScript.getScriptAsString()).isEqualTo(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else return 0 end"
        );
        assertThat(capturedKeys).containsExactly(key);
        assertThat(capturedOwnerId).isEqualTo(actualOwnerId);
    }

    @Test
    void release_shouldWorkWithArbitraryKeyValue() {
        String key = "request_lock:user:123:payment:abc";

        requestLockService.release(key);

        
        ArgumentCaptor<List<String>> keysCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                keysCaptor.capture(),
                anyString()
        );

        assertThat(keysCaptor.getValue()).containsExactly(key);
    }

    @Test
    void release_shouldReuseSameOwnerIdAcrossMultipleReleases() {
        String key1 = "request_lock:key-1";
        String key2 = "request_lock:key-2";

        requestLockService.release(key1);
        requestLockService.release(key2);

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                ownerCaptor.capture()
        );

        List<String> ownerIds = ownerCaptor.getAllValues();
        String actualOwnerId = (String) ReflectionTestUtils.getField(requestLockService, "ownerId");

        assertThat(ownerIds).hasSize(2);
        assertThat(ownerIds.get(0)).isEqualTo(ownerIds.get(1));
        assertThat(ownerIds.get(0)).isEqualTo(actualOwnerId);
    }

    @Test
    void acquireAndRelease_shouldUseSameOwnerIdForSameServiceInstance() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        String key = "request_lock:same-owner-check";

        requestLockService.acquire(key);
        requestLockService.release(key);

        ArgumentCaptor<String> acquireOwnerCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(anyString(), acquireOwnerCaptor.capture(), any(Duration.class));

        ArgumentCaptor<String> releaseOwnerCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), releaseOwnerCaptor.capture());

        assertThat(acquireOwnerCaptor.getValue()).isEqualTo(releaseOwnerCaptor.getValue());
    }
}