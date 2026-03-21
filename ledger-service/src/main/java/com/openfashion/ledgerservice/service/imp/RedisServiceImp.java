package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.TransactionRequest;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisServiceImp implements RedisService {

    private final RedisTemplate<String, String> balanceTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String QUEUE_KEY = "ledger:queue";
    private static final String PROCESSING_KEY = "ledger:queue:processing";

    private static final String POP_BATCH_LUA = """
            local items = redis.call('LPOP', KEYS[1], ARGV[1])
            if items and #items > 0 then
                redis.call('RPUSH', KEYS[2], unpack(items))
                return items
            end
            return {}
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
            redis.call('RPUSH', KEYS[4], ARGV[5])
            
            return 'OK'
            """;

    private String POP_BATCH_LUA_SHA;

    private static final RedisScript<String> LEDGER_SPRING_SCRIPT =
            new DefaultRedisScript<>(LEDGER_SCRIPT, String.class);

    @PostConstruct
    public void loadScript() {
        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(LEDGER_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );

        POP_BATCH_LUA_SHA = balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(POP_BATCH_LUA.getBytes(StandardCharsets.UTF_8))
        );

        log.info("Ledger Lua scripts loaded with SHA");
    }

    @Override
    public Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch) {

        List<TransactionRequest> okList = new ArrayList<>();
        List<TransactionRequest> nsfList = new ArrayList<>();

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

    @Override
    public boolean waitForPersistence(List<TransactionRequest> batch, Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < end) {
            if (allConfirmed(batch)) {
                List<String> confirmationKeys = batch.stream()
                        .map(req -> "confirmed:" + req.getReferenceId())
                        .toList();
                balanceTemplate.delete(confirmationKeys);
                return true;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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

    private void handleScriptResult(String result, TransactionRequest request, List<TransactionRequest> okList, List<TransactionRequest> nsfList) {
        switch (result) {
            case "OK":
                okList.add(request);
                break;
            case "DUPLICATE":
                break;
            case "NSF":
                log.warn("Insufficient funds for transaction {}", request.getReferenceId());
//                throw new InsufficientFundsException(request.getSenderId());
                nsfList.add(request);
                break;
            default:
                throw new RuntimeException("Unexpected Redis response: " + result);
        }
    }

    private boolean allConfirmed(List<TransactionRequest> batch) {
        List<String> confirmationKeys = batch.stream()
                .map(req -> "confirmed:" + req.getReferenceId())
                .toList();

        List<String> results = balanceTemplate.opsForValue().multiGet(confirmationKeys);

        if (results == null || results.isEmpty()) return false;

        for (String result : results) {
            if (result == null || !"true".equals(result)) return false;
        }
        return true;
    }

    @Override
    public List<TransactionRequest> popFromQueue(int batchSize) {
        List<byte[]> rawItems = balanceTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            return connection.scriptingCommands().evalSha(
                    POP_BATCH_LUA_SHA.getBytes(StandardCharsets.UTF_8),
                    ReturnType.MULTI,
                    2,
                    QUEUE_KEY.getBytes(StandardCharsets.UTF_8),
                    PROCESSING_KEY.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(batchSize).getBytes(StandardCharsets.UTF_8)
            );
        });

        if (rawItems == null || rawItems.isEmpty()) return List.of();

        return rawItems.stream()
                .map(bytes -> {
                    try {
                        String json = new String(bytes, StandardCharsets.UTF_8);
                        return objectMapper.readValue(json, TransactionRequest.class);
                    } catch (Exception e) {
                        log.error("CRITICAL: Failed to deserialize transaction in queue.", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String serialize(TransactionRequest transaction) {
        try {
            return objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
}