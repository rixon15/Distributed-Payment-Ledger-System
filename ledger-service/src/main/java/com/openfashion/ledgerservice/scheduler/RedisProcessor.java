package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class RedisProcessor {

    private final RedisService redisService;
    private final LedgerBatchService ledgerBatchService;

    @Scheduled(fixedDelay = 500)
    public void processQueue() {
        List<TransactionRequest> dbBatch = redisService.popFromQueue(100);
        if(dbBatch.isEmpty()) return;

        ledgerBatchService.saveTransactions(dbBatch);

        redisService.signalConfirmation(dbBatch);

    }

}
