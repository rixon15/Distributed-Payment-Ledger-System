package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.RedisMessage;
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
        List<RedisMessage<TransactionRequest>> messages = redisService.popFromQueue(100);
        if (messages.isEmpty()) return;

        List<TransactionRequest> dbBatch = messages.stream()
                .map(RedisMessage::data)
                .toList();

        ledgerBatchService.saveTransactions(dbBatch);

        redisService.signalConfirmation(messages);

    }

}
