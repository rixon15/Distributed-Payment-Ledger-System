package org.example.paymentservice.service.implementation;

import lombok.RequiredArgsConstructor;
import org.example.paymentservice.service.RequestLockService;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class RequestLockServiceImp implements RequestLockService  {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean acquire(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = "1".getBytes(StandardCharsets.UTF_8);

        Boolean success = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        keyBytes,
                        valueBytes,
                        Expiration.seconds(300),
                        RedisStringCommands.SetOption.SET_IF_ABSENT
                ));

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void releaseLock(String key) {
        redisTemplate.delete(key);
    }
}
