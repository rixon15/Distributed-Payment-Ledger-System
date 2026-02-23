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

@Service
@RequiredArgsConstructor
public class RequestLockServiceImp implements RequestLockService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.idempotency.lock-ttl-minutes}")
    private int ttlMinutes;

    private Duration LOCK_TIMEOUT;

    private final String ownerId = UUID.randomUUID().toString();

    @PostConstruct
    public void init() {
        LOCK_TIMEOUT = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public boolean acquire(String key) {
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, ownerId, LOCK_TIMEOUT);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void release(String key) {

        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end";

        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key), ownerId);
    }
}
