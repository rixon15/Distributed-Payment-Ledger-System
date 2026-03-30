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

/**
 * Redis boundary for ledger ingestion and settlement coordination.
 *
 * <p>This contract encapsulates Lua-based batch staging, stream lifecycle operations
 * (read/claim/ack/DLQ), batch completion signaling, and Redis balance synchronization.
 */
public interface RedisService {

    /**
     * Atomically validates and stages a batch using Redis Lua scripts.
     *
     * @param batch normalized ledger requests
     * @param batchId correlation id for batch completion tracking
     * @return partitioned lists keyed by "ok" and "nsf"
     */
    Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch, String batchId);

    /**
     * Reads newly delivered records for this consumer from the transaction stream.
     */
    List<StreamEnvelope<TransactionRequest>> readNewFromStream(int count, Duration block);

    /**
     * Claims stale pending records from the consumer group after minimum idle time.
     */
    List<StreamEnvelope<TransactionRequest>> claimStaleFromStream(int count, Duration minIdle);

    /**
     * Acknowledges stream entries that were already persisted in Postgres.
     */
    AckResult acknowledgePersisted(List<StreamEnvelope<TransactionRequest>> batch);

    /**
     * Moves a failing record to DLQ and acknowledges the original stream entry.
     */
    void moveToDlqAndAck(StreamEnvelope<TransactionRequest> failed, String reason);

    /**
     * Seeds Redis DB snapshot for an account when absent.
     */
    void initializeSnapshotIfMissing(Account account);

    /**
     * Applies confirmed net account balance changes to Redis snapshot/pending delta state.
     */
    void syncRedisBalances(Map<UUID, BigDecimal> netChanges);

    /**
     * Waits until a batch is marked DONE or timeout is reached.
     */
    boolean awaitBatchCompletion(String batchId, Duration timeout);

    /**
     * Increments persisted+acked progress for a batch.
     */
    void markBatchProgress(String batchId, int ackedCount);

    /**
     * Creates metadata for a new batch tracking session.
     */
    BatchToken createBatchToken();

    /**
     * Sets expected successful count used to transition a batch to DONE.
     */
    void setBatchExpectedCount(String batchId, int expectedCount);

}
