package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
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
import java.util.List;
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

    private static final String LEDGER_SCRIPT = """
            -- KEYS[1]: ledger:idempotency:set
            -- KEYS[2]: ledger:db:snapshot (Current DB balances)
            -- KEYS[3]: ledger:pending:delta (Uncommitted changes)
            -- KEYS[4]: ledger:queue (Postgres buffer)
            
            -- ARGV[1]: referenceId
            -- ARGV[2]: senderId
            -- ARGV[3]: amount (e.g., 100.00)
            -- ARGV[4]: receiverId
            -- ARGV[5]: serializedTransaction (JSON)
            
            -- 1. Idempotency Check
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return 'DUPLICATE'
            end
            
            -- 2. Balance Validation (Sender)
            local db_bal = tonumber(redis.call('HGET', KEYS[2], ARGV[2]) or '0')
            local pending_delta = tonumber(redis.call('HGET', KEYS[3], ARGV[2]) or '0')
            local soft_bal = db_bal + pending_delta
            local amount = tonumber(ARGV[3])
            
            if (soft_bal - amount) < 0 then
                return 'NSF'
            end
            
            -- 3. Execute Movement
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

    @PostConstruct
    public void loadScript() {
        // This sends the script to Redis once and gets a hash back
        LEDGER_SCRIPT_SHA = balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(LEDGER_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );
        log.info("Ledger Lua script loaded with SHA: {}", LEDGER_SCRIPT_SHA);
    }

    @Override
    public void processBatchAtomic(List<TransactionRequest> batch) {
        balanceTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (TransactionRequest request : batch) {
                connection.scriptingCommands().eval(
                        LEDGER_SCRIPT_SHA.getBytes(),
                        ReturnType.STATUS,
                        4,
                        IDEMPOTENCY_KEY.getBytes(),
                        DB_SNAPSHOT_KEY.getBytes(),
                        PENDING_DELTA_KEY.getBytes(),
                        QUEUE_KEY.getBytes(),
                        request.getReferenceId().toString().getBytes(),
                        request.getSenderId().toString().getBytes(),
                        request.getAmount().toString().getBytes(),
                        request.getReceiverId().toString().getBytes(),
                        serialize(request).getBytes()
                );
            }

            connection.execute(
                    "WAIT",
                    "1".getBytes(StandardCharsets.UTF_8),
                    "100".getBytes(StandardCharsets.UTF_8));

            return null;
        });
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
