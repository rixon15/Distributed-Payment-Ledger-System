package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.RedisMessage;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.service.RedisService;
import io.lettuce.core.RedisException;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @PostConstruct
    public void loadScript() {
        balanceTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptingCommands().scriptLoad(LEDGER_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );

        balanceTemplate.execute((RedisCallback<String>) conncetion ->
                conncetion.scriptingCommands().scriptLoad(SETTLE_SCRIPT.getBytes(StandardCharsets.UTF_8))
        );

        log.info("Ledger Lua scripts loaded with SHA");
    }

    @Override
    public Map<String, List<TransactionRequest>> processBatchAtomic(List<TransactionRequest> batch) {

        List<TransactionRequest> okList = new ArrayList<>();
        List<TransactionRequest> nsfList = new ArrayList<>();

        List<Object> results = balanceTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(@NonNull RedisOperations operations) throws org.springframework.dao.DataAccessException {
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
                        .map(this::buildConfirmationKey)
                        .toList();
                balanceTemplate.delete(confirmationKeys);
                return true;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void signalConfirmation(List<RedisMessage<TransactionRequest>> messages) {

        balanceTemplate.executePipelined((RedisCallback<Object>) connection -> {

            byte[] processingKeyBytes = PROCESSING_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] trueBytes = "true".getBytes(StandardCharsets.UTF_8);

            for (RedisMessage<TransactionRequest> msg : messages) {
                TransactionRequest requests = msg.data();

                String confirmationKey = buildConfirmationKey(requests);
                connection.stringCommands().setEx(confirmationKey.getBytes(StandardCharsets.UTF_8), 60, trueBytes);

                byte[] rawValueBytes = msg.rawJson().getBytes(StandardCharsets.UTF_8);
                connection.listCommands().lRem(processingKeyBytes, 1, rawValueBytes);
            }

            return null;
        });
    }

    private String buildConfirmationKey(TransactionRequest request) {
        return "confirmed:" + request.getReferenceId().toString() + "-" + request.getType().name();
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
                nsfList.add(request);
                break;
            default:
                throw new RedisException("Unexpected Redis response: " + result);
        }
    }

    private boolean allConfirmed(List<TransactionRequest> batch) {
        List<String> confirmationKeys = batch.stream()
                .map(this::buildConfirmationKey)
                .toList();

        List<String> results = balanceTemplate.opsForValue().multiGet(confirmationKeys);

        if (results == null || results.isEmpty()) return false;

        for (String result : results) {
            if (!"true".equals(result)) return false;
        }
        return true;
    }

    @Override
    public List<RedisMessage<TransactionRequest>> popFromQueue(int batchSize) {

        List<RedisMessage<TransactionRequest>> batch = new ArrayList<>();

        for (int i = 0; i < batchSize; ++i) {

            String rawJson = balanceTemplate.opsForList().rightPopAndLeftPush(QUEUE_KEY, PROCESSING_KEY);

            if (rawJson == null) break;

            try {
                TransactionRequest request = objectMapper.readValue(rawJson, TransactionRequest.class);
                batch.add(new RedisMessage<>(rawJson, request));
            } catch (JsonProcessingException e) {
                log.error("Failed to parse queue item JSON: {}", rawJson, e);
                //Todo: Could move item to DLQ here
            }
        }

        return batch;
    }

    private String serialize(TransactionRequest transaction) {
        try {
            return objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new SerializationFailedException("Serialization failed", e);
        }
    }

    @Override
    public void syncRedisBalances(Map<UUID, BigDecimal> netChanges) {
        if (netChanges.isEmpty()) return;

        balanceTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(@NonNull RedisOperations operations) {
                netChanges.forEach((accountId, delta) ->
                        operations.execute(
                                SETTLE_SPRING_SCRIPT,
                                List.of(DB_SNAPSHOT_KEY, PENDING_DELTA_KEY),
                                accountId.toString(),
                                delta.toPlainString()
                        ));

                return null;
            }
        });
    }
}