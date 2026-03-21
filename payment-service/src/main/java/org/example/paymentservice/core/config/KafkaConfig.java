package org.example.paymentservice.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic transactionRequestTopic() {
        return TopicBuilder.name("transaction.request")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionResponsesTopic() {
        return TopicBuilder.name("transaction.response")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
