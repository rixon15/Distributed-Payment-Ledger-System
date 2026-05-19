package com.openfashion.ledgerservice.integration.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.RedisService;
import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GoldenPathFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "transaction.request";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String STREAM_KEY = "ledger:stream:tx";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RedisService redisService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE postings, outbox_events, transactions, accounts CASCADE"
        );

        redisTemplate.delete(List.of(
                IDEMPOTENCY_KEY,
                DB_SNAPSHOT_KEY,
                PENDING_DELTA_KEY
        ));

        trimStream(STREAM_KEY);
        trimStream(DLQ_STREAM_KEY);
        trimStream(BATCH_DONE_STREAM);
    }

    @Test
    void successfulDeposit_endToEnd() throws Exception {
        UUID userId = UUID.randomUUID();
        Account worldLiquidity = createSystemAccount("WORLD_LIQUIDITY", new BigDecimal("1000.0000"));
        Account user = createUserAccount(userId, "USER_WALLET", new BigDecimal("0.0000"));

        UUID referenceId = UUID.randomUUID();
        String payload = eventJson(
                referenceId,
                "DEPOSIT",
                userId,
                userId,
                "100.0000",
                "POSTED"
        );

        kafkaTemplate.send(TOPIC, referenceId.toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    BigDecimal userBalance = readBalance(user.getId());
                    assertThat(userBalance).isEqualByComparingTo("100.0000");

                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.DEPOSIT))
                            .isPresent()
                            .get()
                            .extracting(Transaction::getStatus)
                            .isEqualTo(TransactionStatus.POSTED);

                    Integer postingCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM postings p JOIN transactions t ON t.id = p.transaction_id " +
                                    "WHERE t.reference_id = ? AND t.type = 'DEPOSIT'",
                            Integer.class,
                            referenceId
                    );

                    assertThat(postingCount).isEqualTo(2);

                    Integer worldDebitCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM postings p " +
                                    "JOIN transactions t ON t.id = p.transaction_id " +
                                    "WHERE t.reference_id = ? AND p.account_id = ? AND p.direction = 'DEBIT'",
                            Integer.class,
                            referenceId,
                            worldLiquidity.getId()
                    );

                    assertThat(worldDebitCount).isEqualTo(1);

                    Integer userCreditCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM postings p " +
                                    "JOIN transactions t ON t.id = p.transaction_id " +
                                    "WHERE t.reference_id = ? AND p.account_id = ? AND p.direction = 'CREDIT'",
                            Integer.class,
                            referenceId,
                            user.getId()
                    );

                    assertThat(userCreditCount).isEqualTo(1);

                    Integer outboxCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM outbox_events " +
                                    "WHERE event_type = 'DEPOSIT' " +
                                    "AND payload->>'referenceId' = ? " +
                                    "AND payload->>'status' = 'POSTED'",
                            Integer.class,
                            referenceId.toString()
                    );

                    assertThat(outboxCount).isEqualTo(1);
                    assertThat(redisHashValue(DB_SNAPSHOT_KEY, user.getId())).isEqualTo("100.0000");
                    assertThat(redisHashValue(PENDING_DELTA_KEY, user.getId())).isIn(null, new BigDecimal("0.0000"));

                });
    }

    @Test
    void successfulTransfer_entToEnd() throws Exception {

        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        Account a = createUserAccount(userA, "A_WALLET", new BigDecimal("100.0000"));
        Account b = createUserAccount(userB, "B_WALLET", new BigDecimal("50.0000"));

        UUID referenceId = UUID.randomUUID();
        String payload = eventJson(
                referenceId,
                "TRANSFER",
                userA,
                userB,
                "25.0000",
                "POSTED"
        );

        kafkaTemplate.send(TOPIC, referenceId.toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(readBalance(a.getId())).isEqualByComparingTo("75.0000");
                    assertThat(readBalance(b.getId())).isEqualByComparingTo("75.0000");

                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.TRANSFER))
                            .isPresent()
                            .get()
                            .extracting(Transaction::getStatus)
                            .isEqualTo(TransactionStatus.POSTED);

                    Integer postingCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM postings p JOIN transactions t ON t.id = p.transaction_id " +
                                    "WHERE t.reference_id = ? AND t.type = 'TRANSFER'",
                            Integer.class,
                            referenceId
                    );
                    assertThat(postingCount).isEqualTo(2);

                    Integer outboxCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM outbox_events " +
                                    "WHERE event_type = 'TRANSFER' " +
                                    "AND payload->>'referenceId' = ? " +
                                    "AND payload->>'status' = 'POSTED'",
                            Integer.class,
                            referenceId.toString()
                    );
                    assertThat(outboxCount).isEqualTo(1);
                });
    }

    @Test
    void successfulWithdrawalReserveThenSettle_endToEnd() throws Exception {
        UUID userId = UUID.randomUUID();
        Account user = createUserAccount(userId, "USER_WALLET", new BigDecimal("100.0000"));
        Account pending = createSystemAccount("PENDING_WITHDRAWAL", new BigDecimal("0.0000"));
        Account world = createSystemAccount("WORLD_LIQUIDITY", new BigDecimal("1000.0000"));

        UUID referenceId = UUID.randomUUID();

        // Reserve leg: eventType = WITHDRAWAL + payload.status = PENDING
        String reservePayload = eventJson(
                referenceId,
                "WITHDRAWAL",
                userId,
                userId,
                "50.0000",
                "PENDING"
        );

        kafkaTemplate.send(TOPIC, referenceId.toString(), reservePayload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(readBalance(user.getId())).isEqualByComparingTo("50.0000");
                    assertThat(readBalance(pending.getId())).isEqualByComparingTo("50.0000");

                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.WITHDRAWAL_RESERVE))
                            .isPresent();
                });

        // Settle leg: same reference, status = POSTED
        String settlePayload = eventJson(
                referenceId,
                "WITHDRAWAL",
                userId,
                userId,
                "50.0000",
                "POSTED"
        );

        kafkaTemplate.send(TOPIC, referenceId.toString(), settlePayload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(readBalance(user.getId())).isEqualByComparingTo("50.0000");
                    assertThat(readBalance(pending.getId())).isEqualByComparingTo("0.0000");
                    assertThat(readBalance(world.getId())).isEqualByComparingTo("1050.0000");

                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.WITHDRAWAL_SETTLE))
                            .isPresent();


                    Integer outboxPosted = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM outbox_events " +
                                    "WHERE payload->>'referenceId' = ? " +
                                    "AND payload->>'status' = 'POSTED'",
                            Integer.class,
                            referenceId.toString()
                    );
                    assertThat(outboxPosted).isGreaterThanOrEqualTo(2);
                });
    }

    @Test
    void withdrawalReserve_thenFailedStatus_releasesFundsBackToUser() throws Exception {
        UUID userId = UUID.randomUUID();
        Account user = createUserAccount(userId, "USER_WALLET", new BigDecimal("100.0000"));
        Account pending = createSystemAccount("PENDING_WITHDRAWAL", new BigDecimal("0"));

        UUID referenceId = UUID.randomUUID();

        String reserve = eventJson(referenceId, "WITHDRAWAL", userId, userId, "50.0000", "PENDING");
        kafkaTemplate.send(TOPIC, referenceId.toString(), reserve).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(readBalance(user.getId())).isEqualByComparingTo("50.0000");
                    assertThat(readBalance(pending.getId())).isEqualByComparingTo("50.0000");
                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.WITHDRAWAL_RESERVE)).isPresent();
                });

        String release = eventJson(referenceId, "WITHDRAWAL", userId, userId, "50.0000", "FAILED");
        kafkaTemplate.send(TOPIC, referenceId.toString(), release).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(readBalance(user.getId())).isEqualByComparingTo("100.0000");
                    assertThat(readBalance(pending.getId())).isEqualByComparingTo("0.0000");
                    assertThat(transactionRepository.findByReferenceIdAndType(referenceId, TransactionType.WITHDRAWAL_RELEASE)).isPresent();
                });
    }

    private void trimStream(String streamKey) {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            byte[] key = redisTemplate.getStringSerializer().serialize(streamKey);
            if (key != null && Boolean.TRUE.equals(connection.keyCommands().exists(key))) {
                connection.streamCommands().xTrim(key, 0L, false);
            }
            return null;
        });
    }

    private Account createUserAccount(UUID userId, String name, BigDecimal balance) {

        return accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setUserId(userId);
                    account.setName(name);
                    account.setType(AccountType.ASSET);
                    account.setCurrency(CurrencyType.USD);
                    account.setBalance(balance);
                    account.setStatus(AccountStatus.ACTIVE);
                    Account saved = accountRepository.saveAndFlush(account);
                    redisService.initializeSnapshotIfMissing(saved);
                    return saved;
                });

    }

    private Account createSystemAccount(String name, BigDecimal balance) {

        return accountRepository.findByNameAndCurrency(name, CurrencyType.USD)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setUserId(UUID.randomUUID());
                    account.setName(name);
                    account.setType(AccountType.EQUITY);
                    account.setCurrency(CurrencyType.USD);
                    account.setBalance(balance);
                    account.setStatus(AccountStatus.ACTIVE);
                    Account saved = accountRepository.saveAndFlush(account);
                    redisService.initializeSnapshotIfMissing(saved);
                    return saved;
                });
    }

    private BigDecimal readBalance(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
    }

    private BigDecimal redisHashValue(String key, UUID field) {
        Object raw = redisTemplate.opsForHash().get(key, field.toString());
        if (raw == null) {
            return null;
        }
        return MoneyUtil.format(new BigDecimal(raw.toString()));
    }

    private String eventJson(
            UUID referenceId,
            String eventType,
            UUID senderId,
            UUID receiverId,
            String amount,
            String payloadStatus
    ) throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", eventType,
                "aggregatedId", referenceId,
                "timestamp", Instant.now().toString(),
                "payload", Map.of(
                        "senderId", senderId,
                        "receiverId", receiverId,
                        "amount", amount,
                        "currency", "USD",
                        "status", payloadStatus,
                        "userMessage", "e2e-test",
                        "timestamp", Instant.now().toString(),
                        "metadata", Map.of("source", "integration-test")
                )
        );
        return objectMapper.writeValueAsString(event);
    }

}
