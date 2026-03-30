package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.consumer.BatchToken;
import com.openfashion.ledgerservice.dto.redis.AckResult;
import com.openfashion.ledgerservice.dto.redis.StreamEnvelope;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.service.RedisService;
import io.lettuce.core.RedisException;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Redis-backed implementation of ledger staging and stream orchestration.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>execute Lua scripts for atomic idempotency and NSF checks,</li>
 *   <li>append accepted requests to {@code ledger:stream:tx},</li>
 *   <li>manage consumer-group read/claim/ack and DLQ handoff,</li>
 *   <li>track batch completion metadata and done notifications,</li>
 *   <li>sync Redis balance snapshots after confirmed DB persistence.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisServiceImp implements RedisService {

    private final RedisTemplate<String, String> balanceTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";

    private static final String STREAM_KEY = "ledger:stream:tx";
    private static final String STREAM_GROUP = "ledger-stream-group";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_META_PREFIX = "ledger:batch:meta:";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";
    private static final Duration BATCH_META_TTL = Duration.ofMinutes(10);

    private static final String BATCH_ID_FIELD = "batchId";
    private static final String STATUS_FIELD = "status";
    private static final String DONE_STATUS = "DONE";
    private static final Duration MAX_AWAIT_BLOCK_SLICE = Duration.ofSeconds(1);

    private String consumerName;
    private static final String PAYLOAD = "payload";

    private static final String MARK_PROGRESS_SCRIPT = """
            -- KEYS[1]=batchMetaKey, KEYS[2]=batchDoneStream
            -- ARGV[1]=batchId, ARGV[2]=ackedCount
            local processed = tonumber(redis.call('HINCRBY', KEYS[1], 'processed', ARGV[2]))
            local expected = tonumber(redis.call('HGET', KEYS[1], 'expected') or '0')
            local status = redis.call('HGET', KEYS[1], 'status') or 'PENDING'
            
            if expected > 0 and processed >= expected and status ~= 'DONE' then
                redis.call('HSET', KEYS[1], 'status', 'DONE')
                redis.call('XADD', KEYS[2], '*', 'MAXLEN', '~', '20000', '*',
                    'batchId', ARGV[1],
                    'status', 'DONE',
                    'processed', tostring(processed),
                    'expected', tostring(expected))
                return 1
            end
            
            return 0
            """;

    private static final String SET_EXPECTED_SCRIPT = """
            -- KEYS[1]=batchMetaKey, KEYS[2]=batchDoneStream
            -- ARGV[1]=batchId, ARGV[2]=expectedCount
            local expected = tonumber(ARGV[2])
            redis.call('HSET', KEYS[1], 'expected', ARGV[2])
            
            local processed = tonumber(redis.call('HGET', KEYS[1], 'processed') or '0')
            local status = redis.call('HGET', KEYS[1], 'status') or 'PENDING'
            
            if status ~= 'DONE' and (expected == 0 or (expected > 0 and processed >= expected)) then
                redis.call('HSET', KEYS[1], 'status', 'DONE')
                redis.call('XADD', KEYS[2], 'MAXLEN', '~', '20000', '*',
                    'batchId', ARGV[1],
                    'status', 'DONE',
                    'processed', tostring(processed),
                    'expected', tostring(expected))
                return 1
            end
            
            return 0
            """;

    private static final String LEDGER_SCRIPT = """
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return 'DUPLICATE'
            end
            
            if ARGV[6] == '1' then
                local db_bal = tonumber(redis.call('HGET', KEYS[2], ARGV[2]) or '0')
                local pending_delta = tonumber(redis.call('HGET', KEYS[3], ARGV[2]) or '0')
                local soft_bal = db_bal + pending_delta
                local amount = tonumber(ARGV[3])
            
                if (soft_bal - amount) < 0 then
                    return 'NSF'
                end
            end
            
            local amount = tonumber(ARGV[3])
            redis.call('HINCRBYFLOAT', KEYS[3], ARGV[2], -amount)
            redis.call('HINCRBYFLOAT', KEYS[3], ARGV[4], amount)
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('XADD', KEYS[4], '*', 'payload', ARGV[5], 'idempotencyKey', ARGV[1], 'batchId', ARGV[7])
            
            return 'OK'
            """;

    private static final String SETTLE_SCRIPT = """
            -- KEYS[1]: DB_SNAPSHOT_KEY, KEYS[2]: PENDING_DELTA_KEY
            -- ARGV[1]: Account ID, ARGV[2]: Amount to settle
            redis.call('HINCRBYFLOAT', KEYS[1], ARGV[1], ARGV[2])
            redis.call('HINCRBYFLOAT', KEYS[2], ARGV[1], -ARGV[2])
            return 'OK'
            """;

    private static final RedisScript<String> LEDGER_SPRING_SCRIPT =
            new DefaultRedisScript<>(LEDGER_SCRIPT, String.class);
    private static final RedisScript<String> SETTLE_SPRING_SCRIPT =
            new DefaultRedisScript<>(SETTLE_SCRIPT, String.class);
    private static final RedisScript<Long> MARK_PROGRESS_SPRING_SCRIPT =
            new DefaultRedisScript<>(MARK_PROGRESS_SCRIPT, Long.class);
    private static final RedisScript<Long> SET_EXPECTED_SPRING_SCRIPT =
            new DefaultRedisScript<>(SET_EXPECTED_SCRIPT, Long.class);

    /**
     * Initializes consumer identity, preloads Lua scripts, and ensures stream consumer group exists.
     */
    @PostConstruct
    public void init() {

        try {
            consumerName = InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException _) {
            consumerName = "ledger-consumer-" + UUID.randomUUID().toString().substring(0, 8);
            log.warn("Failed to generate hostname-based consumer name, using fallback: {}", consumerName);
        }

        log.info("Consumer name initialized: {}", consumerName);

        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(LEDGER_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );
        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(SETTLE_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );
        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(MARK_PROGRESS_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );
        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(SET_EXPECTED_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );

        try {
            balanceTemplate.execute((RedisCallback<String>) connection -> {
                connection.streamCommands().xGroupCreate(STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                        STREAM_GROUP,
                        ReadOffset.from("0"),
                        true);
                return "OK";
            });
            log.info("Stream consumer group created: {}", STREAM_GROUP);
        } catch (Exception e) {
            String message = e.getMessage();


            if (message != null && message.contains("BUSYGROUP")) {
                log.warn("Unexpected error creating consumer group (will continue if BUSYGROUP): {}", e.getMessage());
            } else {
                log.info("Consumer group already exists: {}", STREAM_GROUP);
            }
        }


        log.info("Ledger Lua scripts loaded with SHA");
    }


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
    @Override
    public Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch, String batchId) {

        List<TransactionRequest> okList = new ArrayList<>();
        List<TransactionRequest> nsfList = new ArrayList<>();

        List<Object> results = balanceTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                for (TransactionRequest request : batch) {

                    boolean checkNsf = switch (request.getType()) {
                        case DEPOSIT, WITHDRAWAL_SETTLE, WITHDRAWAL_RELEASE -> false;
                        default -> true;
                    };

                    String checkNsfStr = checkNsf ? "1" : "0";
                    String compositeIdempotencyKey = request.getReferenceId().toString() + "-" + request.getType().name();

                    operations.execute(
                            LEDGER_SPRING_SCRIPT,
                            List.of(IDEMPOTENCY_KEY, DB_SNAPSHOT_KEY, PENDING_DELTA_KEY, STREAM_KEY),
                            compositeIdempotencyKey,
                            request.getDebitAccountId().toString(),
                            request.getAmount().toPlainString(),
                            request.getCreditAccountId().toString(),
                            serialize(request),
                            checkNsfStr,
                            batchId
                    );
                }
                return null;
            }
        });

        for (int i = 0; i < batch.size(); i++) {
            String resultStr = (String) results.get(i);
            handleScriptResult(resultStr, batch.get(i), okList, nsfList);
        }

        return Map.of(
                "ok", okList,
                "nsf", nsfList
        );
    }

    public void initializeSnapshotIfMissing(Account account) {
        balanceTemplate.opsForHash().putIfAbsent(
                DB_SNAPSHOT_KEY,
                account.getId().toString(),
                account.getBalance().toPlainString()
        );
    }

    /**
     * Reads newly delivered stream entries for this consumer and deserializes payloads.
     *
     * <p>Malformed entries are moved to DLQ and acknowledged to avoid blocking the stream.
     */
    @Override
    public List<StreamEnvelope<TransactionRequest>> readNewFromStream(int count, Duration block) {
        List<StreamEnvelope<TransactionRequest>> envelopes = new ArrayList<>();

        try {
            List<MapRecord<String, Object, Object>> messages = balanceTemplate.opsForStream().read(
                    Consumer.from(STREAM_GROUP, consumerName),
                    StreamReadOptions.empty().count(count).block(block),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );

            if (messages == null || messages.isEmpty()) {
                return envelopes;
            }

            for (MapRecord<String, Object, Object> entry : messages) {
                String streamId = entry.getId().toString();
                String batchId = asString(entry.getValue().get(BATCH_ID_FIELD));


                try {

                    TransactionRequest request = parsePayload(entry);
                    envelopes.add(new StreamEnvelope<>(streamId, batchId, asString(entry.getValue().get(PAYLOAD)), request, 1));

                    log.debug("Parsed stream record {}: referenceId={}", streamId, request.getReferenceId());

                } catch (Exception e) {
                    log.error("Failed to parse stream record: {}", entry.getId().getValue(), e);
                    moveToDlqAndAck(
                            new StreamEnvelope<>(streamId, batchId, asString(entry.getValue().get(PAYLOAD)), null, 1),
                            "PARSE_ERROR: " + e.getMessage()
                    );
                }
            }
        } catch (Exception e) {
            log.error("Error reading from stream: {}", e.getMessage(), e);
        }

        return envelopes;
    }

    /**
     * Claims stale pending entries from the consumer group after a minimum idle threshold.
     *
     * <p>Delivery count is preserved in returned envelopes for retry-cutoff logic.
     */
    @Override
    public List<StreamEnvelope<TransactionRequest>> claimStaleFromStream(int count, Duration minIdle) {
        List<StreamEnvelope<TransactionRequest>> envelopes = new ArrayList<>();

        PendingMessages pendingMessages = balanceTemplate.opsForStream().pending(
                STREAM_KEY,
                STREAM_GROUP,
                Range.unbounded(),
                count
        );

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return envelopes;
        }

        Map<String, Long> deliveryCountById = new HashMap<>();
        List<RecordId> staleIds = new ArrayList<>();

        for (PendingMessage msg : pendingMessages) {
            if (msg.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                staleIds.add(msg.getId());

                long deliveries = Math.max(1L, msg.getTotalDeliveryCount());
                deliveryCountById.put(msg.getId().getValue(), deliveries);
            }
        }

        if (staleIds.isEmpty()) {
            return envelopes;
        }

        List<MapRecord<String, Object, Object>> claimed = balanceTemplate.opsForStream().claim(
                STREAM_KEY,
                STREAM_GROUP,
                consumerName,
                minIdle,
                staleIds.toArray(RecordId[]::new)
        );

        for (MapRecord<String, Object, Object> entry : claimed) {
            String streamId = entry.getId().getValue();
            String payloadJson = asString(entry.getValue().get(PAYLOAD));
            String batchId = asString(entry.getValue().get(BATCH_ID_FIELD));
            long deliveryCount = deliveryCountById.getOrDefault(streamId, 1L);

            if (payloadJson == null) {
                moveToDlqAndAck(
                        new StreamEnvelope<>(streamId, batchId, null, null, 1),
                        "MISSING_PAYLOAD_STALE"
                );

                continue;
            }

            try {
                TransactionRequest request = objectMapper.readValue(payloadJson, TransactionRequest.class);
                envelopes.add(new StreamEnvelope<>(streamId, batchId, payloadJson, request, deliveryCount));
                log.debug("Claimed stale stream record {}: referenceId = {}, deliveries = {}", streamId, request, deliveryCount);
            } catch (Exception e) {
                moveToDlqAndAck(
                        new StreamEnvelope<>(streamId, batchId, payloadJson, null, deliveryCount),
                        "PARSE_ERROR_STALE: " + e.getMessage()
                );
                log.error("Failed to parse claimed stale stream record: {}", streamId, e);
            }

        }

        return envelopes;
    }

    /**
     * Acknowledges persisted stream entries in the consumer group.
     *
     * @return ack summary including partial-ack diagnostics
     */
    @Override
    public AckResult acknowledgePersisted(List<StreamEnvelope<TransactionRequest>> batch) {
        if (batch.isEmpty()) return new AckResult(0, 0, List.of(), true, null);

        List<RecordId> recordIds = batch.stream()
                .map(env -> RecordId.of(env.streamId()))
                .toList();

        try {

            Long ackCountObj = balanceTemplate.opsForStream().acknowledge(STREAM_KEY, STREAM_GROUP, recordIds.toArray(RecordId[]::new));
            log.debug("Acknowledged {} stream entries", ackCountObj);

            int requested = recordIds.size();
            int acked = ackCountObj == null ? 0 : ackCountObj.intValue();

            if (acked != requested) {
                List<String> attemptedIds = batch.stream().map(StreamEnvelope::streamId).toList();
                return new AckResult(requested, acked, attemptedIds, false, "Partial XACK");
            }

            return new AckResult(requested, acked, List.of(), true, null);
        } catch (Exception e) {
            log.error("Error acknowledging stream entries", e);

            return new AckResult(recordIds.size(), 0,
                    batch.stream().map(StreamEnvelope::streamId).toList(),
                    false, e.getMessage());
        }
    }

    /**
     * Moves a failed entry to {@code ledger:stream:tx:dlq} and acknowledges the original entry.
     *
     * @param failed failing stream envelope
     * @param reason short machine-readable failure reason
     */
    @Override
    public void moveToDlqAndAck(StreamEnvelope<TransactionRequest> failed, String reason) {
        try {
            balanceTemplate.opsForStream().add(
                    DLQ_STREAM_KEY,
                    Map.of(
                            "streamId", failed.streamId(),
                            PAYLOAD, failed.rawJson() != null ? failed.rawJson() : "null",
                            "reason", reason,
                            "timestamp", System.currentTimeMillis() + ""
                    )
            );
            log.warn("Moved stream record {} to DLQ: {}", failed.streamId(), reason);

            balanceTemplate.opsForStream().acknowledge(STREAM_KEY, STREAM_GROUP, RecordId.of(failed.streamId()));
            log.debug("Acknowledged failed entry {}", failed.streamId());
        } catch (Exception e) {
            log.error("Error moving record to DLQ: {}", failed.streamId(), e);
        }
    }

    /**
     * Marks persisted progress for a batch and emits a DONE signal when processed >= expected.
     */
    @Override
    public void markBatchProgress(String batchId, int ackedCount) {

        if (ackedCount <= 0) {
            return;
        }

        String key = BATCH_META_PREFIX + batchId;

        balanceTemplate.execute(
                MARK_PROGRESS_SPRING_SCRIPT,
                List.of(key, BATCH_DONE_STREAM),
                batchId,
                String.valueOf(ackedCount)
        );

        balanceTemplate.expire(key, BATCH_META_TTL);

    }

    /**
     * Waits until a batch reaches DONE status or timeout expires.
     *
     * <p>Completion is detected via metadata hash status and done-stream events.
     */
    @Override
    public boolean awaitBatchCompletion(String batchId, Duration timeout) {

        String metaKey = batchMetaKey(batchId);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        if (isBatchDone(metaKey)) {
            return true;
        }

        while (System.nanoTime() < deadlineNanos) {
            long remainingNanos = deadlineNanos - System.nanoTime();

            if (remainingNanos <= 0) {
                return false;
            }

            Duration remaining = Duration.ofNanos(remainingNanos);
            Duration block = remaining.compareTo(MAX_AWAIT_BLOCK_SLICE) > 0 ? MAX_AWAIT_BLOCK_SLICE : remaining;

            List<MapRecord<String, Object, Object>> events = balanceTemplate.opsForStream().read(
                    StreamReadOptions.empty().count(100).block(block),
                    StreamOffset.create(BATCH_DONE_STREAM, ReadOffset.latest())
            );

            // Re-check hash status every loop. This prevents missed event-edge cases
            if (isBatchDone(metaKey)) {
                return true;
            }

            if (events == null || events.isEmpty()) {
                continue;
            }

            for (MapRecord<String, Object, Object> event : events) {
                String eventBatchId = asString(event.getValue().get(BATCH_ID_FIELD));
                String eventStatus = asString(event.getValue().get(STATUS_FIELD));

                if (batchId.equals(eventBatchId) && DONE_STATUS.equals(eventStatus)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Creates and initializes Redis metadata for a new batch tracking lifecycle.
     */
    @Override
    public BatchToken createBatchToken() {
        String batchId = UUID.randomUUID().toString();
        String key = batchMetaKey(batchId);

        balanceTemplate.opsForHash().putAll(key, Map.of(
                "expected", "0",
                "processed", "0",
                STATUS_FIELD, "PENDING"
        ));

        balanceTemplate.expire(key, BATCH_META_TTL);

        return new BatchToken(batchId, 0);
    }

    /**
     * Sets expected accepted count used to transition batch status to DONE.
     */
    @Override
    public void setBatchExpectedCount(String batchId, int expectedCount) {

        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount must be >= 0");
        }

        String key = batchMetaKey(batchId);

        balanceTemplate.execute(
                SET_EXPECTED_SPRING_SCRIPT,
                List.of(key, BATCH_DONE_STREAM),
                batchId,
                String.valueOf(expectedCount)
        );

        balanceTemplate.expire(key, BATCH_META_TTL);
    }

    /**
     * Applies confirmed net balance deltas to Redis snapshot and pending-delta hashes.
     *
     * <p>Called only after DB balance updates are successful.
     */
    @Override
    public void syncRedisBalances(Map<UUID, BigDecimal> netChanges) {
        if (netChanges.isEmpty()) return;

        balanceTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(@NonNull RedisOperations operations) {
                netChanges.forEach((accountId, delta) -> {
                    BigDecimal normalizedDelta = MoneyUtil.format(delta);

                    operations.execute(
                            SETTLE_SPRING_SCRIPT,
                            List.of(DB_SNAPSHOT_KEY, PENDING_DELTA_KEY),
                            accountId.toString(),
                            normalizedDelta.toPlainString()
                    );
                });

                return null;
            }
        });
    }

    private String asString(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> String.valueOf(value);
        };
    }

    private TransactionRequest parsePayload(MapRecord<String, Object, Object> entry) throws IOException {
        String payloadJson = asString(entry.getValue().get(PAYLOAD));
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("Missing payload for stream entry " + entry.getId().getValue());
        }
        return objectMapper.readValue(payloadJson, TransactionRequest.class);
    }


    private boolean isBatchDone(String metaKey) {
        String currentStatus = asString(balanceTemplate.opsForHash().get(metaKey, STATUS_FIELD));
        return DONE_STATUS.equals(currentStatus);
    }

    private String batchMetaKey(String batchId) {
        return BATCH_META_PREFIX + batchId;
    }

    private void handleScriptResult(String result, TransactionRequest
            request, List<TransactionRequest> okList, List<TransactionRequest> nsfList) {
        switch (result) {
            case "OK":
                okList.add(request);
                break;
            case "DUPLICATE":
                break;
            case "NSF":
                log.warn("Insufficient funds for transaction {}", request.getReferenceId());
                nsfList.add(request);
                break;
            default:
                throw new RedisException("Unexpected Redis response: " + result);
        }
    }

    private String serialize(TransactionRequest transaction) {
        try {
            return objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new SerializationFailedException("Serialization failed", e);
        }
    }
}