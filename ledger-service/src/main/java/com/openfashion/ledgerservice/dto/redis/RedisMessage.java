package com.openfashion.ledgerservice.dto.redis;

public record RedisMessage<T>(
        String rawJson,
        T data
) {
}
