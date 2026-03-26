package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.consumer.BatchToken;
import com.openfashion.ledgerservice.dto.redis.AckResult;
import com.openfashion.ledgerservice.dto.redis.StreamEnvelope;
import com.openfashion.ledgerservice.model.Account;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RedisService {
    Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch, String batchId);


    List<StreamEnvelope<TransactionRequest>> readNewFromStream(int count, Duration block);

    List<StreamEnvelope<TransactionRequest>> claimStaleFromStream(int count, Duration minIdle);

    AckResult acknowledgePersisted(List<StreamEnvelope<TransactionRequest>> batch);

    void moveToDlqAndAck(StreamEnvelope<TransactionRequest> failed, String reason);

    void initializeSnapshotIfMissing(Account account);

    void syncRedisBalances(Map<UUID, BigDecimal> netChanges);


    boolean awaitBatchCompletion(String batchId, Duration timeout);

    void markBatchProgress(String batchId, int ackedCount);

    BatchToken createBatchToken();

    void setBatchExpectedCount(String batchId, int expectedCount);

}
