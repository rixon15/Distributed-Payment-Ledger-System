package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

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

    @Bean
    public RedisTemplate<String, PendingTransaction> transactionLogTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, PendingTransaction> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());

        // Use Jackson to serialize the DTO to JSON in Redis
        JacksonJsonRedisSerializer<PendingTransaction> serializer =
                new JacksonJsonRedisSerializer<>(PendingTransaction.class);
        template.setValueSerializer(serializer);
        return template;
    }

}
