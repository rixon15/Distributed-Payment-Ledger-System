package com.openfashion.ledgerservice.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis template configuration for ledger balance and stream operations.
 *
 * <p>Uses string serializers to keep Lua scripts and numeric hash operations
 * compatible with raw Redis string representations.
 */
@Configuration
public class RedisConfig {

    /**
     * Creates RedisTemplate used by Redis service implementation.
     *
     * @param connectionFactory Spring-managed Redis connection factory
     * @return string-based Redis template
     */
    @Bean
    public RedisTemplate<String, String> balanceTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Using String serializers for both because HINCRBYFLOAT
        // expects raw string representations of numbers
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
