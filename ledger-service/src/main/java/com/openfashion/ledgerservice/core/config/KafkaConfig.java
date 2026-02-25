package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.WithdrawalEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    private Map<String, Object> commonConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual Ack for safety
        return props;
    }

    @Bean
    public ConsumerFactory<String, TransactionInitiatedEvent> initiatedConsumerFactory() {
        JacksonJsonDeserializer<TransactionInitiatedEvent> jsonDeserializer =
                new JacksonJsonDeserializer<>(TransactionInitiatedEvent.class);
        jsonDeserializer.addTrustedPackages("com.openfashion.ledgerservice.dto.event");

        return new DefaultKafkaConsumerFactory<>(
                commonConsumerProps(),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(jsonDeserializer)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> initiatedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(initiatedConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // --- WITHDRAWAL EVENTS (Unified Flow) ---
    @Bean
    public ConsumerFactory<String, WithdrawalEvent> withdrawalConsumerFactory() {
        JacksonJsonDeserializer<WithdrawalEvent> jsonDeserializer =
                new JacksonJsonDeserializer<>(WithdrawalEvent.class);
        jsonDeserializer.addTrustedPackages("com.openfashion.ledgerservice.dto.event");

        return new DefaultKafkaConsumerFactory<>(
                commonConsumerProps(),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(jsonDeserializer)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WithdrawalEvent> withdrawalKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, WithdrawalEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(withdrawalConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // --- PRODUCER (Outbox Poller use) ---
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // Atomic writes
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // Guaranteed delivery
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}