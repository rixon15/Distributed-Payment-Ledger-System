package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Confluent Parallel Consumer for high-throughput ingestion from Kafka.
 *
 * <p>The processor is tuned for key ordering, bounded concurrency, and batched polling.
 */
@Configuration
@RequiredArgsConstructor
public class ParallelConsumerConfig {

    /**
     * Creates the {@code ParallelStreamProcessor} used by transaction ingestion listener.
     *
     * @param nativeConsumer Kafka consumer created from application consumer factory
     * @return configured stream processor
     */
    @Bean
    public ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer(
            Consumer<String, TransactionInitiatedEvent> nativeConsumer
    ) {
        var options = ParallelConsumerOptions.<String, TransactionInitiatedEvent>builder()
                .ordering(ParallelConsumerOptions.ProcessingOrder.KEY)
                .maxConcurrency(100)
                .consumer(nativeConsumer)
                .batchSize(150)
                .commitMode(ParallelConsumerOptions.CommitMode.PERIODIC_CONSUMER_SYNC)
                .build();

        return ParallelStreamProcessor.createEosStreamProcessor(options);
    }

}
