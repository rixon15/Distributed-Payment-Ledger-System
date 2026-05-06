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
     * Atomically validates and stages a batch using Redis Lua.
     *
     * <p>For each request:
     * <ul>
     *   <li>deduplicates by composite idempotency key,</li>
     *   <li>optionally performs soft-balance NSF checks,</li>
     *   <li>writes accepted records to {@code ledger:stream:tx}.</li>
     * </ul>
     *
     * @param batch normalized requests from strategy mapping
     * @param batchId correlation id for completion tracking
     * @return map with keys {@code "ok"} and {@code "nsf"}
     */
    Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch, String batchId);

    /**
     * Reads newly delivered stream entries for this consumer and deserializes payloads.
     *
     * <p>Malformed entries are moved to DLQ and acknowledged to avoid blocking the stream.
     */
    List<StreamEnvelope<TransactionRequest>> readNewFromStream(int count, Duration block);

    /**
     * Claims stale pending entries from the consumer group after a minimum idle threshold.
     *
     * <p>Delivery count is preserved in returned envelopes for retry-cutoff logic.
     */
    List<StreamEnvelope<TransactionRequest>> claimStaleFromStream(int count, Duration minIdle);

    /**
     * Acknowledges persisted stream entries in the consumer group.
     *
     * @return ack summary including partial-ack diagnostics
     */
    AckResult acknowledgePersisted(List<StreamEnvelope<TransactionRequest>> batch);

    /**
     * Moves a failed entry to {@code ledger:stream:tx:dlq} and acknowledges the original entry.
     *
     * @param failed failing stream envelope
     * @param reason short machine-readable failure reason
     */
    void moveToDlqAndAck(StreamEnvelope<TransactionRequest> failed, String reason);

    /**
     * Seeds Redis DB snapshot for an account when absent.
     */
    void initializeSnapshotIfMissing(Account account);

    /**
     * Applies confirmed net balance deltas to Redis snapshot and pending-delta hashes.
     *
     * <p>Called only after DB balance updates are successful.
     */
    void syncRedisBalances(Map<UUID, BigDecimal> netChanges);

    /**
     * Waits until a batch reaches DONE status or timeout expires.
     *
     * <p>Completion is detected via metadata hash status and done-stream events.
     */
    boolean awaitBatchCompletion(String batchId, Duration timeout);

    /**
     * Marks persisted progress for a batch and emits a DONE signal when processed >= expected.
     */
    void markBatchProgress(String batchId, int ackedCount);

    /**
     * Creates and initializes Redis metadata for a new batch tracking lifecycle.
     */
    BatchToken createBatchToken();

    /**
     * Sets expected accepted count used to transition batch status to DONE.
     */
    void setBatchExpectedCount(String batchId, int expectedCount);

}
