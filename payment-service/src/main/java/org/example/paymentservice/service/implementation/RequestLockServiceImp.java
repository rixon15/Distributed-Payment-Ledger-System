package org.example.paymentservice.service.implementation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.service.RequestLockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Redis-backed idempotency lock implementation using owner-checked release semantics.
 */
@Service
@RequiredArgsConstructor
public class RequestLockServiceImp implements RequestLockService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.idempotency.lock-ttl-minutes}")
    private int ttlMinutes;

    private Duration lockTimeout;

    private final String ownerId = UUID.randomUUID().toString();

    /**
     * Initializes lock TTL from configuration.
     */
    @PostConstruct
    public void init() {
        lockTimeout = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Acquires lock key via SETNX with expiration.
     */
    @Override
    public boolean acquire(String key) {
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, ownerId, lockTimeout);

        return Boolean.TRUE.equals(success);
    }

    /**
     * Releases lock only if current instance is lock owner (Lua compare-and-delete).
     */
    @Override
    public void release(String key) {

        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end";

        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key), ownerId);
    }
}
