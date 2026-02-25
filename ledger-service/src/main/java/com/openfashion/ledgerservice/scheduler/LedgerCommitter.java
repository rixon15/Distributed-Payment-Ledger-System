package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerCommitter {

    private final RedisTemplate<String, PendingTransaction> logTemplate;

    private final LedgerBatchService ledgerBatchService;

    private static final String QUEUE_KEY = "ledger:queue";
    private static final String PROCESSING_KEY = "ledger:queue:processing";
    private static final int BATCH_SIZE = 1000;

    @PostConstruct
    public void recoverStuckTransactions() {
        Long stuckCount = logTemplate.opsForList().size(PROCESSING_KEY);
        if (stuckCount != null && stuckCount > 0) {
            log.warn("Found {} stuck transaction in processing queue. Recovering...", stuckCount);

            for (int i = 0; i < stuckCount; i++) {
                logTemplate.opsForList().rightPopAndLeftPush(PROCESSING_KEY, QUEUE_KEY);
            }
        }
    }

    @Scheduled(fixedDelay = 500)
    public void commitLedger() {
        List<PendingTransaction> batch = fetchBatch();

        if (batch.isEmpty()) return;

        try {

            List<UUID> processedIds = ledgerBatchService.findAlreadyProcessedIds(batch);

            if (!processedIds.isEmpty()) {
                log.warn("Recovery: Found {} transactions already in DB. Cleaning Redis", processedIds.size());

                batch = batch.stream().filter(pt -> !processedIds.contains(pt.referenceId())).toList();
            }

            if (!batch.isEmpty()) {
                ledgerBatchService.processBatch(batch);
            }

            logTemplate.delete(PROCESSING_KEY);
        } catch (Exception e) {
            log.error("Failed to commit ledger batch. Transactions will remain in Redis for retry.", e);
            //These transactions should be moved back to the main queue or should be moved to DLQ
            //We'll leave it as it is for now.
        }
    }

    private List<PendingTransaction> fetchBatch() {
        List<PendingTransaction> batch = new ArrayList<>();

        for (int i = 0; i < BATCH_SIZE; ++i) {
            PendingTransaction transaction = logTemplate.opsForList()
                    .rightPopAndLeftPush(QUEUE_KEY, PROCESSING_KEY);

            if (transaction == null) break;

            batch.add(transaction);
        }

        return batch;
    }


}
