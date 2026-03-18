package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.service.LedgerService;
import com.openfashion.ledgerservice.service.RedisService;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventListener {

    private final LedgerService ledgerService;
    private final ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer;
    private final RedisService redisService;

    @PostConstruct
    public void startConsuming() {
        parallelConsumer.subscribe(List.of("transaction.initiated"));

        parallelConsumer.poll(context -> {
            // In version 0.5.x, .stream() provides the records in the batch
            // when .batchSize() is set in the options.
            List<TransactionRequest> batch = context.stream()
                    .map(recordContext -> {
                        // Access the value directly from the RecordContext
                        TransactionRequest req = mapEventToRequest(recordContext.value());
                        ledgerService.resolveAccounts(req);
                        return req;
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

    private TransactionRequest mapEventToRequest(TransactionInitiatedEvent event) {
        TransactionRequest request = new TransactionRequest();
        TransactionPayload payload = event.payload(); // Access the nested payload

        request.setReferenceId(event.referenceId()); // This was event.aggregatedId
        request.setType(TransactionType.valueOf(payload.type()));
        request.setCurrency(CurrencyType.valueOf(payload.currency()));
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());
        request.setAmount(payload.amount());
        request.setMetadata(payload.userMessage());

        return request;
    }

}
