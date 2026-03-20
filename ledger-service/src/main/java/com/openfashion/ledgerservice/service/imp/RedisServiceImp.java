package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.core.exceptions.InsufficientFundsException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.service.RedisService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisServiceImp implements RedisService {

    private final RedisTemplate<String, String> balanceTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AGGREGATE_KEY = "ledger:pending:balance";
//    private static final String QUEUE_KEY = "ledger:queue";

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String QUEUE_KEY = "ledger:queue";
    private static final String PROCESSING_KEY = "ledger:queue:processing";

    private static final String POP_BATCH_LUA = """
            local items = redis.call('LPOP', KEYS[1], ARGV[1])
            if items and #items > 0 then
                -- unpack() safely expands the array into arguments for RPUSH
                redis.call('RPUSH', KEYS[2], unpack(items))
                return items
            end
            return {}
            """;

    private static final String LEDGER_SCRIPT = """
            -- KEYS[1]: ledger:idempotency:set
            -- KEYS[2]: ledger:db:snapshot (Current DB balances)
            -- KEYS[3]: ledger:pending:delta (Uncommitted changes)
            -- KEYS[4]: ledger:queue (Postgres buffer)
            
            -- ARGV[1]: referenceId
            -- ARGV[2]: debitAccountId
            -- ARGV[3]: amount
            -- ARGV[4]: creditAccountId
            -- ARGV[5]: serializedTransaction (JSON)
            -- ARGV[6]: checkNsfFlag ('1' to check, '0' to bypass)
            
            -- 1. Idempotency Check
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return 'DUPLICATE'
            end
            
            -- 2. Balance Validation (Only if flag is '1')
            if ARGV[6] == '1' then
                local db_bal = tonumber(redis.call('HGET', KEYS[2], ARGV[2]) or '0')
                local pending_delta = tonumber(redis.call('HGET', KEYS[3], ARGV[2]) or '0')
                local soft_bal = db_bal + pending_delta
                local amount = tonumber(ARGV[3])
            
                if (soft_bal - amount) < 0 then
                    return 'NSF'
                end
            end
            
            -- 3. Execute Movement
            local amount = tonumber(ARGV[3])
            -- Debit Sender
            redis.call('HINCRBYFLOAT', KEYS[3], ARGV[2], -amount)
            -- Credit Receiver
            redis.call('HINCRBYFLOAT', KEYS[3], ARGV[4], amount)
            -- Mark as Processed
            redis.call('SADD', KEYS[1], ARGV[1])
            -- Push to Postgres Queue
            redis.call('RPUSH', KEYS[4], ARGV[5])
            
            return 'OK'
            """;

    private String LEDGER_SCRIPT_SHA;
    private String POP_BATCH_LUA_SHA;

    private static final RedisScript<String> LEDGER_SPRING_SCRIPT =
            new DefaultRedisScript<>(LEDGER_SCRIPT, String.class);

    @PostConstruct
    public void loadScript() {
        // This sends the script to Redis once and gets a hash back
        LEDGER_SCRIPT_SHA = balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(LEDGER_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );

        POP_BATCH_LUA_SHA = balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(POP_BATCH_LUA.getBytes(StandardCharsets.UTF_8))
        );

        log.info("Ledger Lua scripts loaded with SHA");
    }

    @Override
    public void processBatchAtomic(List<TransactionRequest> batch) {
        // 1. Run Pipeline
        List<Object> results = balanceTemplate.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations) throws org.springframework.dao.DataAccessException {
                for (TransactionRequest request : batch) {

                    boolean checkNsf = switch (request.getType()) {
                        case DEPOSIT, WITHDRAWAL_SETTLE, WITHDRAWAL_RELEASE -> false;
                        default -> true;
                    };

                    String checkNsfStr = checkNsf ? "1" : "0";

                    String compositeIdempotencyKey = request.getReferenceId().toString() + "-" + request.getType().name();

                    operations.execute(
                            LEDGER_SPRING_SCRIPT,
                            List.of(IDEMPOTENCY_KEY, DB_SNAPSHOT_KEY, PENDING_DELTA_KEY, QUEUE_KEY),
                            compositeIdempotencyKey,
                            request.getDebitAccountId().toString(),
                            request.getAmount().toPlainString(),
                            request.getCreditAccountId().toString(),
                            serialize(request),
                            checkNsfStr
                    );
                }
                return null;
            }
        });

        //TODO: wait command for other replicas

        // 2. Process results (No WAIT command needed for local single-node!)
        for (int i = 0; i < batch.size(); i++) {
            String resultStr = (String) results.get(i);
            handleScriptResult(resultStr, batch.get(i));
        }
    }

    public void initializeSnapshotIfMissing(Account account) {
        balanceTemplate.opsForHash().putIfAbsent(
                DB_SNAPSHOT_KEY,
                account.getId().toString(),
                account.getBalance().toPlainString()
        );
    }

    @Override
    public boolean waitForPersistence(List<TransactionRequest> batch, Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < end) {
            if (allConfirmed(batch)) {
                // Cleanup: Delete the keys now that we've seen them
                List<String> confirmationKeys = batch.stream()
                        .map(req -> "confirmed:" + req.getReferenceId())
                        .toList();
                balanceTemplate.delete(confirmationKeys);

                return true;
            }

            try {
                // 100ms sleep prevents CPU thrashing while Virtual Thread waits
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false; // Timeout reached
    }

    public void signalConfirmation(List<TransactionRequest> batch) {
        balanceTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (TransactionRequest req : batch) {
                String key = "confirmed:" + req.getReferenceId();
                connection.setEx(key.getBytes(StandardCharsets.UTF_8), 60, "true".getBytes(StandardCharsets.UTF_8));
            }

            connection.del(PROCESSING_KEY.getBytes(StandardCharsets.UTF_8));

            return null;
        });
    }

    private void handleScriptResult(String result, TransactionRequest request) {
        switch (result) {
            case "OK":
            case "DUPLICATE":
                // If it's a duplicate, Redis already processed it previously,
                // but we still let it pass here so the listener waits for the DB to confirm it.
                break;
            case "NSF":
                log.warn("Insufficient funds for transaction {}", request.getReferenceId());
                throw new InsufficientFundsException(request.getSenderId());
            default:
                throw new RuntimeException("Unexpected Redis response: " + result);
        }
    }

    private boolean allConfirmed(List<TransactionRequest> batch) {
        List<String> confirmationKeys = batch.stream()
                .map(req -> "confirmed:" + req.getReferenceId())
                .toList();

        List<String> results = balanceTemplate.opsForValue().multiGet(confirmationKeys);

        if (results == null || results.isEmpty()) {
            return false;
        }

        for (String result : results) {
            if (result == null || !"true".equals(result)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public List<TransactionRequest> popFromQueue(int batchSize) {
        List<byte[]> rawItems = balanceTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            return connection.scriptingCommands().evalSha(
                    POP_BATCH_LUA_SHA.getBytes(StandardCharsets.UTF_8),
                    ReturnType.MULTI,
                    2, // Number of keys
                    QUEUE_KEY.getBytes(StandardCharsets.UTF_8),
                    PROCESSING_KEY.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(batchSize).getBytes(StandardCharsets.UTF_8)
            );
        });

        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }

        return rawItems.stream()
                .map(bytes -> {
                    try {
                        String json = new String(bytes, StandardCharsets.UTF_8);
                        return objectMapper.readValue(json, TransactionRequest.class);
                    } catch (Exception e) {
                        log.error("CRITICAL: Failed to deserialize transaction in queue. Payload: {}", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }


    private static final String COMMIT_LUA_STRING = """
            for i, json in ipairs(ARGV) do local tx = cjson.decode(json);
            
                -- Reverse the debit pending change
                local new_debit = redis.call('HINCRBYFLOAT', KEYS[1], tx.debitAccountId, tx.amount);
                if tonumber(new_debit) == 0 then
                    redis.call('HDEL', KEYS[1], tx.debitAccountId);
                end
            
                -- Reverse the credit pending change
                local new_credit = redis.call('HINCRBYFLOAT', KEYS[1], tx.creditAccountId, '-' .. tx.amount);
                if tonumber(new_credit) == 0 then
                    redis.call('HDEL', KEYS[1], tx.creditAccountId);
                end
            end
            return true;
            """;

    private static final RedisScript<Boolean> COMMIT_SCRIPT =
            new DefaultRedisScript<>(COMMIT_LUA_STRING, Boolean.class);

    @Override
    public void bufferTransactions(PendingTransaction transaction) {

        String script =
                "redis.call('HINCRBYFLOAT', KEYS[1], ARGV[1], ARGV[2]); " + // Update Debit Account (negate amount)
                        "redis.call('HINCRBYFLOAT', KEYS[1], ARGV[3], ARGV[4]); " + // Update Credit Account
                        "redis.call('RPUSH', KEYS[2], ARGV[5]); " +                // Push to Log Queue
                        "return true;";

        balanceTemplate.execute(
                new DefaultRedisScript<>(script, Boolean.class),
                List.of(AGGREGATE_KEY, QUEUE_KEY),
                transaction.debitAccountId().toString(), transaction.amount().negate().toPlainString(),
                transaction.creditAccountId().toString(), transaction.amount().toPlainString(),
                serialize(transaction)
        );
    }

    @Override
    public BigDecimal getPendingNetChanges(UUID accountId) {

        Object value = balanceTemplate.opsForHash().get(AGGREGATE_KEY, accountId.toString());
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private String serialize(PendingTransaction transaction) {
        try {
            return objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    private String serialize(TransactionRequest transaction) {
        try {
            return objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    @Override
    public void commitFromBuffer(List<PendingTransaction> transactions) {


        Object[] args = transactions.stream().map(this::serialize).toArray();

        balanceTemplate.execute(
                COMMIT_SCRIPT,
                List.of(AGGREGATE_KEY),
                args
        );

    }
}
