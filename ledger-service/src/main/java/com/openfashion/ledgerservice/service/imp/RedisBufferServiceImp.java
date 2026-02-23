package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.service.RedisBufferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RedisBufferServiceImp implements RedisBufferService {

    private final RedisTemplate<String, String> balanceTemplate;
    private final RedisTemplate<String, PendingTransaction> logTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AGGREGATE_KEY = "ledger:pending:balance";
    private static final String QUEUE_KEY = "ledger:queue";

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
}
