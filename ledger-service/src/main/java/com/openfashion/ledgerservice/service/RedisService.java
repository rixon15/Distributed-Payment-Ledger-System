package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.RedisMessage;
import com.openfashion.ledgerservice.model.Account;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RedisService {
    Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch);

    boolean waitForPersistence(List<TransactionRequest> batch, Duration time);

    void signalConfirmation(List<RedisMessage<TransactionRequest>> batch);

    List<RedisMessage<TransactionRequest>> popFromQueue(int batchSize);

    void initializeSnapshotIfMissing(Account account);

    void syncRedisBalances(Map<UUID, BigDecimal> netChanges);
}
