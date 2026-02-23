package org.example.paymentservice;

import org.example.paymentservice.service.implementation.RequestLockServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.idempotency.lock-ttl-minutes=5",
        "scheduling.enabled=false"
})
@Testcontainers
public class RequestLockServiceTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RequestLockServiceImp lockService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final String TEST_KEY = "lock:test-request";

    @BeforeEach
    void cleanRedis() {
        redisTemplate.delete(TEST_KEY);
    }

    @Test
    @DisplayName("Should acquire lock when key is free")
    void testAcquire_Success() {
        boolean acquired = lockService.acquire(TEST_KEY);

        assertThat(acquired).isTrue();
        assertThat(redisTemplate.opsForValue().get(TEST_KEY)).isNotNull();
    }

    @Test
    @DisplayName("Should fail to acquire lock when key is already taken")
    void testAcquire_Duplicate() {
        lockService.acquire(TEST_KEY);

        boolean acquired = lockService.acquire(TEST_KEY);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("Should release lock only if owned by the same service instance")
    void testRelease_OwnershipSecurity() {
        String differentOwner = "someone-else";
        redisTemplate.opsForValue().set(TEST_KEY, differentOwner);

        lockService.release(TEST_KEY);

        assertThat(redisTemplate.opsForValue().get(TEST_KEY)).isEqualTo(differentOwner);
    }

    @Test
    @DisplayName("Should successfully release lock when owned by us")
    void testRelease_Success() {
        lockService.acquire(TEST_KEY);
        assertThat(redisTemplate.hasKey(TEST_KEY)).isTrue();

        lockService.release(TEST_KEY);

        assertThat(redisTemplate.hasKey(TEST_KEY)).isFalse();
    }

    @Test
    @DisplayName("Lock should have a TTL (Time To Live)")
    void testLockExpiration() {
        lockService.acquire(TEST_KEY);

        Long expire = redisTemplate.getExpire(TEST_KEY);

        assertThat(expire).isNotNull();
        assertThat(expire).isGreaterThan(0);
        assertThat(expire).isLessThanOrEqualTo(300);
    }

}
