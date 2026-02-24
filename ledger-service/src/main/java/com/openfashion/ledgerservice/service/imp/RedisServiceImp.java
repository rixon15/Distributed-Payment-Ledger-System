package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RedisServiceImp implements RedisService {

    private final RedisTemplate<String, String> balanceTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AGGREGATE_KEY = "ledger:pending:balance";
    private static final String QUEUE_KEY = "ledger:queue";

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
