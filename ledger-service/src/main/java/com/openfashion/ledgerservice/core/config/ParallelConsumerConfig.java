package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

@Configuration
@RequiredArgsConstructor
public class ParallelConsumerConfig {

    @Bean
    public ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer(
            Consumer<String, TransactionInitiatedEvent> nativeConsumer
    ) {
        var options = ParallelConsumerOptions.<String, TransactionInitiatedEvent>builder()
                .ordering(ParallelConsumerOptions.ProcessingOrder.KEY)
                .maxConcurrency(2000)
                .consumer(nativeConsumer)
                .batchSize(50)
                .commitMode(ParallelConsumerOptions.CommitMode.PERIODIC_CONSUMER_SYNC)
                .build();

        return ParallelStreamProcessor.createEosStreamProcessor(options);
    }

}
