package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.core.exceptions.AccountInactiveException;
import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.DbTimeoutException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.consumer.BatchToken;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.service.DlqPublisher;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import com.openfashion.ledgerservice.service.strategy.LedgerStrategy;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Kafka ingestion entrypoint for ledger processing.
 *
 * <p>This component subscribes to {@code transaction.request}, maps inbound events
 * through {@link com.openfashion.ledgerservice.service.strategy.LedgerStrategy} implementations,
 * stages validated requests in Redis, and waits for downstream DB persistence completion.
 *
 * <p>Pipeline summary:
 * <ol>
 *   <li>Consume batch from Kafka via Parallel Consumer.</li>
 *   <li>Map event type to strategy and build normalized {@code TransactionRequest} items.</li>
 *   <li>Run Redis Lua pre-processing ({@code processBatchAtomic}) for idempotency + NSF checks.</li>
 *   <li>Persist NSF rejections immediately; wait for accepted batch completion signal.</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventListener {

    private final ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer;
    private final RedisService redisService;

    private final List<LedgerStrategy> strategyList;
    private final Map<TransactionType, LedgerStrategy> strategyMap = new EnumMap<>(TransactionType.class);
    private final LedgerBatchService ledgerBatchService;
    private final DlqPublisher dlqPublisher;

    /**
     * Initializes strategy mapping and starts asynchronous Kafka consumption.
     */
    @PostConstruct
    public void init() {
        initStrategies();
        startConsuming();
    }

    /**
     * Builds an enum-dispatch map for all supported {@code TransactionType} values.
     *
     * <p>Any type without a registered strategy is logged and remains unsupported at runtime.
     */
    private void initStrategies() {

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

    /**
     * Starts polling {@code transaction.request} and submits mapped batches into Redis staging.
     *
     * <p>Throws a timeout exception when accepted records are not confirmed as persisted
     * within the configured wait window, allowing safe retry behavior.
     */
    private void startConsuming() {
        parallelConsumer.subscribe(List.of("transaction.request"));

        parallelConsumer.poll(context -> {

            List<TransactionRequest> validRequests = new ArrayList<>();
            List<TransactionRequest> validationFailures = new ArrayList<>();

            context.stream().forEach(recordContext -> {
                TransactionInitiatedEvent event = recordContext.value();

                if (event == null) {
                    dlqPublisher.publishMalformedToDlq(recordContext);
                    return;
                }

                LedgerStrategy strategy = strategyMap.get(event.eventType());

                if (strategy == null) {
                    dlqPublisher.publishUnsupportedTypeToDlq(recordContext, String.valueOf(event.eventType()));
                    return;
                }

                if (!strategy.isValidTransaction(event)) {
                    log.warn("Business validation failed for referenceId={}", event.referenceId());
                    validationFailures.add(strategy.createRejectedRequest(event));
                    dlqPublisher.publishBusinessViolationMessageToDlq(recordContext);
                    return;
                }

                try {
                    validRequests.add(strategy.mapToRequest(event));
                } catch (AccountNotFoundException | MissingSystemAccountException | AccountInactiveException e) {
                    log.warn("Account resolution failed for referenceId={}: {}", event.referenceId(), e.getMessage());
                    validationFailures.add(strategy.createRejectedRequest(event));
                    dlqPublisher.publishBusinessViolationMessageToDlq(recordContext);
                } catch (Exception e) {
                    // If it's a completely unexpected system error, THEN it goes to the DLQ
                    log.error("Unexpected error mapping request", e);
                    dlqPublisher.publishMalformedToDlq(recordContext);
                }
            });

            if (!validationFailures.isEmpty()) {
                ledgerBatchService.persistRejected(validationFailures, TransactionStatus.REJECTED_VALIDATION);
            }

            if (validRequests.isEmpty()) return;

            log.info("Processing valid Kafka batch of size: {}", validRequests.size());

            BatchToken token = redisService.createBatchToken();

            Map<String, List<TransactionRequest>> results = redisService.processBatchAtomic(validRequests, token.batchId());

            int okCount = results.getOrDefault("ok", List.of()).size();

            redisService.setBatchExpectedCount(token.batchId(), okCount);


            ledgerBatchService.persistRejected(results.get("nsf"), TransactionStatus.REJECTED_NSF);


            boolean success = true;

            if (okCount > 0) {
                // High-load timeout (30s)
                success = redisService.awaitBatchCompletion(token.batchId(), Duration.ofSeconds(30));
            }

            if (!success) {
                throw new DbTimeoutException();
            }
        });
    }

}