package com.openfashion.ledgerservice.integration.businessBoundaries;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.TransactionNotFoundException;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.dto.event.TransactionResultEvent;
import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.RedisService;
import org.hibernate.AssertionFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DepositBoundaryIntegrationTest extends AbstractIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String STREAM_KEY = "ledger:stream:tx";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String TRANSACTION_REQUEST_TOPIC = "transaction.request";

    private Account userUsd;
    private Account worldAccount;
    private UUID userId;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PostingRepository postingRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisService redisService;

    @BeforeEach
    void setup() {
        cleanState();

        userId = UUID.randomUUID();

        userUsd = createUserAccount(
                "wallet-user-usd",
                userId,
                CurrencyType.USD,
                new BigDecimal("0.0000")
        );

        worldAccount = createSystemAccount(
                "WORLD_LIQUIDITY",
                CurrencyType.USD,
                new BigDecimal("1000000.0000")
        );
    }

    @Test
    void ZeroAmount_deposit_isRejectedValidation_andNoMutation() {
        BigDecimal originalBalance = accountBalance(userId, CurrencyType.USD);

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("0.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.DEPOSIT
        );
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo(originalBalance);
    }

    @Test
    void negativeAmount_deposit_isRejectedValidation_adndNoMutation() {
        BigDecimal originalBalance = accountBalance(userId, CurrencyType.USD);

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("-10.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo(originalBalance);
    }

    @Test
    void missingSenderId_deposit_isRejectedValidation() {

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                null,
                new BigDecimal("10.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);

    }

    @Test
    void missingUserUsdAccount_deposit_isRejectedValidation_andNoMutation() {
        UUID euroOnlyUserId = UUID.randomUUID();

        createUserAccount(
                "wallet-user-eur",
                euroOnlyUserId,
                CurrencyType.EUR,
                new BigDecimal("10.0000")
        );

        BigDecimal originalAmount = accountBalance(euroOnlyUserId, CurrencyType.EUR);

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                euroOnlyUserId,
                new BigDecimal("50.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertThat(accountBalance(euroOnlyUserId, CurrencyType.EUR)).isEqualByComparingTo(originalAmount);

    }

    @Test
    void validDeposit_posts_asControlCase() {

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("100.0000"),
                CurrencyType.USD
        );


        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.POSTED, TransactionType.DEPOSIT);
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo("100.0000");
        assertPostingCountForReference(event.referenceId(), 2);
        assertOutboxExists(event.referenceId(), TransactionStatus.POSTED, TransactionType.DEPOSIT);

    }

    @Test
    void missingWorldLiquidityAccount_deposit_isRejectedValidation() {
        accountRepository.delete(worldAccount);

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("50.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo("0.0000");
    }

    @Test
    void inactiveSenderAccount_deposit_isRejectedValidation() {

        userUsd.setStatus(AccountStatus.FROZEN);
        accountRepository.save(userUsd);

        BigDecimal originalBalance = accountBalance(userId, CurrencyType.USD);

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("75.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo(originalBalance);

    }

    @Test
    void deposit_wrongCurrencyForExitingUser_isRejectedValidation() {

        TransactionInitiatedEvent event = depositEvent(
                UUID.randomUUID(),
                userId,
                new BigDecimal("50.0000"),
                CurrencyType.EUR
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.DEPOSIT);
        assertThat(accountBalance(userId, CurrencyType.USD)).isEqualByComparingTo("0.0000");

    }

    private void assertPostingCountForReference(UUID referenceId, int expectedAmount) {
        List<Posting> postings = postingRepository.findAllByTransactionReferenceId(referenceId);
        assertThat(postings).hasSize(expectedAmount);
    }

    private TransactionInitiatedEvent depositEvent(
            UUID referenceId,
            UUID accountId,
            BigDecimal amount,
            CurrencyType currencyType
    ) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.DEPOSIT,
                referenceId,
                Instant.now(),
                new TransactionPayload(
                        accountId,
                        accountId,
                        amount,
                        currencyType,
                        TransactionStatus.PENDING,
                        "Test transaction",
                        Instant.now(),
                        null
                )
        );
    }

    private void cleanState() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, ledger_db.public.outbox_events, transactions, accounts CASCADE");

        redisTemplate.delete(List.of(
                IDEMPOTENCY_KEY,
                DB_SNAPSHOT_KEY,
                PENDING_DELTA_KEY
        ));

        trimStream(STREAM_KEY);
        trimStream(DLQ_STREAM_KEY);
        trimStream(BATCH_DONE_STREAM);

    }

    private void trimStream(String streamKey) {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            byte[] key = redisTemplate.getStringSerializer().serialize(streamKey);
            if (Boolean.TRUE.equals(connection.keyCommands().exists(key))) {
                // equivalent to XTRIM <stream> MAXLEN 0
                connection.streamCommands().xTrim(key, 0L, false);
            }
            return null;
        });
    }

    private Account createUserAccount(
            String name,
            UUID userId,
            CurrencyType currencyType,
            BigDecimal balance
    ) {
        Account newAccount = new Account();
        newAccount.setUserId(userId);
        newAccount.setName(name);
        newAccount.setType(AccountType.ASSET);
        newAccount.setCurrency(currencyType);
        newAccount.setBalance(balance);
        newAccount.setCreatedAt(Instant.now());
        newAccount.setUpdatedAt(Instant.now());

        Account savedAccount = accountRepository.save(newAccount);
        redisService.initializeSnapshotIfMissing(savedAccount);

        return savedAccount;
    }

    private Account createSystemAccount(String name, CurrencyType currencyType, BigDecimal balance) {

        return accountRepository.findByNameAndCurrency(name, CurrencyType.USD)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setUserId(UUID.randomUUID());
                    account.setName(name);
                    account.setType(AccountType.EQUITY);
                    account.setCurrency(currencyType);
                    account.setBalance(balance);
                    account.setStatus(AccountStatus.ACTIVE);
                    Account saved = accountRepository.saveAndFlush(account);
                    redisService.initializeSnapshotIfMissing(saved);
                    return saved;
                });
    }

    private BigDecimal accountBalance(UUID userId, CurrencyType currencyType) {
        Account account = accountRepository.findByUserIdAndCurrency(userId, currencyType)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        return MoneyUtil.format(account.getBalance());
    }

    private void assertTransactionStatus(UUID referenceId, TransactionStatus status, TransactionType type) {
        Transaction transaction = transactionRepository.findByReferenceIdAndType(referenceId, type)
                .orElseThrow(() -> new TransactionNotFoundException(referenceId));

        assertThat(transaction.getStatus()).isEqualTo(status);
    }

    private void assertNoPostingsForReference(UUID referenceId) {
        List<Posting> postings = postingRepository.findAllByTransactionReferenceId(referenceId);

        assertThat(postings).isEmpty();
    }

    private void assertOutboxExists(UUID referenceId, TransactionStatus expectedStatus, TransactionType type) {
        OutboxEvent outbox = outboxRepository.findAll().stream()
                .filter(e -> e.getEventType() == type)
                .filter(e -> {
                    try {
                        TransactionResultEvent event =
                                objectMapper.readValue(e.getPayload(), TransactionResultEvent.class);
                        return event.referenceId().equals(referenceId);
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AssertionFailure("No outbox event found for referenceId=" + referenceId));

        try {
            TransactionResultEvent resultEvent =
                    objectMapper.readValue(outbox.getPayload(), TransactionResultEvent.class);

            assertThat(resultEvent.referenceId()).isEqualTo(referenceId);
            assertThat(resultEvent.type()).isEqualTo(type);
            assertThat(resultEvent.status()).isEqualTo(expectedStatus);
            assertThat(resultEvent.timestamp()).isNotNull();
        } catch (Exception e) {
            throw new AssertionFailure("Failed to deserialize outbox payload for referenceId=" + referenceId);
        }
    }

    private void publishTransactionRequest(TransactionInitiatedEvent event) {
        String key = event.referenceId().toString();
        kafkaTemplate.send(TRANSACTION_REQUEST_TOPIC, key, objectMapper.writeValueAsString(event)).join();
    }

    private void awaitTerminalState(UUID referenceId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM transactions WHERE reference_id = ? ORDER BY created_at DESC LIMIT 1",
                    ps -> ps.setObject(1, referenceId),
                    rs -> rs.next() ? rs.getString("status") : null
            );

            if (status != null && isTerminalStatus(TransactionStatus.valueOf(status))) {
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for terminal transaction state", e);
            }
        }

        jdbcTemplate.query(
                "SELECT status FROM transactions WHERE reference_id = ? ORDER BY created_at DESC LIMIT 1",
                ps -> ps.setObject(1, referenceId),
                rs -> rs.next() ? rs.getString("status") : null
        );

        throw new AssertionError(
                "Timed out waiting fro terminal state for referenceId=" + referenceId
        );
    }

    private boolean isTerminalStatus(TransactionStatus status) {
        return switch (status) {
            case POSTED,
                 REJECTED_NSF,
                 REJECTED_INACTIVE,
                 FAILED,
                 REJECTED_RISK,
                 REJECTED_VALIDATION -> true;
            default -> false;
        };
    }
}
