package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import org.apache.kafka.clients.consumer.Consumer;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka bean configuration for ledger event consumption and production.
 *
 * <p>Provides:
 * <ul>
 *   <li>typed consumer deserialization for inbound transaction events,</li>
 *   <li>manual ack listener container settings,</li>
 *   <li>idempotent producer settings for outbound publishing.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Common consumer properties shared by typed consumer factories.
     */
    private Map<String, Object> commonConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

    /**
     * ConsumerFactory for {@code TransactionInitiatedEvent} payloads.
     */
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

    /**
     * Listener container factory with manual immediate ack mode.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> initiatedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(initiatedConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /**
     * ProducerFactory configured for idempotent delivery and all-acks durability.
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * KafkaTemplate used by components that publish outbound events.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Native Kafka consumer used by Parallel Consumer configuration.
     */
    @Bean
    public Consumer<String, TransactionInitiatedEvent> nativeConsumer(
            ConsumerFactory<String, TransactionInitiatedEvent> initiatedEventConsumerFactory
    ) {
        return initiatedEventConsumerFactory.createConsumer();
    }
}