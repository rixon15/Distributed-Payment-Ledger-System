package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.core.exceptions.UnsupportedTransactionException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.service.LedgerService;
import com.openfashion.ledgerservice.service.RedisService;
import com.openfashion.ledgerservice.service.strategy.LedgerStrategy;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventListener {

    private final LedgerService ledgerService;
    private final ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer;
    private final RedisService redisService;

    private final List<LedgerStrategy> strategyList;
    private final Map<TransactionType, LedgerStrategy> strategyMap = new EnumMap<>(TransactionType.class);

    @Order(1)
    @PostConstruct
    public void init() {

        log.info("Initializing Transaction Strategies...");

        for (TransactionType type : TransactionType.values()) {
            strategyList.stream()
                    .filter(strategy -> strategy.supports(type))
                    .findFirst()
                    .ifPresentOrElse(
                            strategy -> strategyMap.put(type, strategy),
                            () -> log.warn("No strategy found for transaction type: {}", type)
                    );
        }

        log.info("Ledger Strategy Map initialized with {} strategies", strategyMap.size());
    }

    @Order(2)
    @PostConstruct
    public void startConsuming() {
        parallelConsumer.subscribe(List.of("transaction.request"));

        parallelConsumer.poll(context -> {
            // In version 0.5.x, .stream() provides the records in the batch
            // when .batchSize() is set in the options.
            List<TransactionRequest> batch = context.stream()
                    .map(recordContext -> {
                        // Access the value directly from the RecordContext
                        TransactionInitiatedEvent event = recordContext.value();

                        LedgerStrategy strategy = strategyMap.get(event.eventType());

                        if (strategy == null) {
                            throw new UnsupportedTransactionException(event.eventType());
                        }

                        return strategy.mapToRequest(event);

                    })
                    .toList();

            if (batch.isEmpty()) return;

            log.info("Processing Kafka batch of size: {}", batch.size());

            redisService.processBatchAtomic(batch);

            // High-load timeout (30s)
            boolean success = redisService.waitForPersistence(batch, Duration.ofSeconds(30));
            if (!success) {
                throw new RuntimeException("Db commit timeout - retrying batch");
            }
        });
    }

}