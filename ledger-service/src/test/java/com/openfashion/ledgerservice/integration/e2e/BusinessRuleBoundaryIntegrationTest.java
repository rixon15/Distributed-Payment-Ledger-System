package com.openfashion.ledgerservice.integration.e2e;

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
class BusinessRuleBoundaryIntegrationTest extends AbstractIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String STREAM_KEY = "ledger:stream:tx";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String TRANSACTION_REQUEST_TOPIC = "transaction.request";

    private Account userAUsd;
    private Account userBUsd;
    private Account userBEur;
    private final UUID userAId = UUID.randomUUID();
    private final UUID userBId = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private PostingRepository postingRepository;
    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisService redisService;


    @BeforeEach
    void setup() {

        cleanState();

        userAUsd = createUserAccount(
                "wallet-a-usd",
                userAId,
                CurrencyType.USD,
                new BigDecimal("100.0000")
        );

        userBUsd = createUserAccount(
                "wallet-b-usd",
                userBId,
                CurrencyType.USD,
                new BigDecimal("25.0000")
        );

        userBEur = createUserAccount(
                "wallet-b-eur",
                userBId,
                CurrencyType.EUR,
                new BigDecimal("10.0000")
        );
    }

    @Test
    void selfTransfer_sameDebitAndCredit_isRejectedAndNoMutation() {
        BigDecimal originalBalance = accountBalance(userAId, CurrencyType.USD);

        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userAId,
                new BigDecimal("10.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.TRANSFER);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                userAUsd.getId(),
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.TRANSFER
        );
        assertThat(accountBalance(userAId, event.payload().currency())).isEqualByComparingTo(originalBalance);
    }

    @Test
    void zeroAmount_transfer_isRejectedAndNoMutation() {
        BigDecimal senderOriginalBalance = accountBalance(userAId, CurrencyType.USD);
        BigDecimal receiverOriginalBalance = accountBalance(userBId, CurrencyType.USD);

        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userBId,
                new BigDecimal("0.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.TRANSFER);
        assertThat(accountBalance(userAId, CurrencyType.USD)).isEqualByComparingTo(senderOriginalBalance);
        assertThat(accountBalance(userBId, CurrencyType.USD)).isEqualByComparingTo(receiverOriginalBalance);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                userAUsd.getId(),
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.TRANSFER
        );
    }

    @Test
    void negativeAmount_transfer_isRejectedAndNoMutation() {
        BigDecimal senderOriginalBalance = accountBalance(userAId, CurrencyType.USD);
        BigDecimal receiverOriginalBalance = accountBalance(userBId, CurrencyType.USD);

        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userBId,
                new BigDecimal("-50.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.TRANSFER);
        assertThat(accountBalance(userAId, CurrencyType.USD)).isEqualByComparingTo(senderOriginalBalance);
        assertThat(accountBalance(userBId, CurrencyType.USD)).isEqualByComparingTo(receiverOriginalBalance);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                userAUsd.getId(),
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.TRANSFER
        );
    }

    @Test
    void senderClosed_transfer_isRejected() {
        BigDecimal senderOriginalBalance = accountBalance(userAId, CurrencyType.USD);
        BigDecimal receiverOriginalBalance = accountBalance(userBId, CurrencyType.USD);

        userAUsd.setStatus(AccountStatus.CLOSED);
        accountRepository.saveAndFlush(userAUsd);

        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userBId,
                new BigDecimal("10.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.TRANSFER);
        assertThat(accountBalance(userAId, CurrencyType.USD)).isEqualByComparingTo(senderOriginalBalance);
        assertThat(accountBalance(userBId, CurrencyType.USD)).isEqualByComparingTo(receiverOriginalBalance);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                userAUsd.getId(),
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.TRANSFER
        );
    }

    @Test
    void currencyMismatch_senderUsdReceiverEur_transferRejectedNoMutation() {
        BigDecimal senderOriginalBalance = accountBalance(userAId, CurrencyType.USD);
        BigDecimal receiverOriginalBalance = accountBalance(userBId, CurrencyType.EUR);

        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userBId,
                new BigDecimal("10.0000"),
                CurrencyType.EUR
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.REJECTED_VALIDATION, TransactionType.TRANSFER);
        assertThat(accountBalance(userAId, CurrencyType.USD)).isEqualByComparingTo(senderOriginalBalance);
        assertThat(accountBalance(userBId, CurrencyType.EUR)).isEqualByComparingTo(receiverOriginalBalance);
        assertNoPostingsForReference(event.referenceId());
        assertOutboxExists(
                userAId,
                event.referenceId(),
                TransactionStatus.REJECTED_VALIDATION,
                TransactionType.TRANSFER
        );
    }

    @Test
    void validTransfer_stillPosts_asControlCase() {
        TransactionInitiatedEvent event = transferEvent(
                UUID.randomUUID(),
                userAId,
                userBId,
                new BigDecimal("25.0000"),
                CurrencyType.USD
        );

        publishTransactionRequest(event);
        awaitTerminalState(event.referenceId(), WAIT_TIMEOUT);

        assertTransactionStatus(event.referenceId(), TransactionStatus.POSTED, TransactionType.TRANSFER);
        assertThat(accountBalance(userAId, CurrencyType.USD)).isEqualByComparingTo("75.0000");
        assertThat(accountBalance(userBId, CurrencyType.USD)).isEqualByComparingTo("50.0000");
        assertOutboxExists(
                userAUsd.getId(),
                event.referenceId(),
                TransactionStatus.POSTED,
                TransactionType.TRANSFER
        );
    }


    private TransactionInitiatedEvent transferEvent(
            UUID referenceId,
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount,
            CurrencyType currencyType
    ) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                referenceId,
                Instant.now(),
                new TransactionPayload(
                        debitAccountId,
                        creditAccountId,
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

    private BigDecimal accountBalance(UUID userId, CurrencyType currencyType) {
        Account account = accountRepository.findByUserIdAndCurrency(userId, currencyType)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        return MoneyUtil.format(account.getBalance());
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

    private void assertTransactionStatus(UUID referenceId, TransactionStatus status, TransactionType type) {
        Transaction transaction = transactionRepository.findByReferenceIdAndType(referenceId, type)
                .orElseThrow(() -> new TransactionNotFoundException(referenceId));

        assertThat(transaction.getStatus()).isEqualTo(status);
    }

    private void assertNoPostingsForReference(UUID referenceId) {
        List<Posting> postings = postingRepository.findAllByTransactionReferenceId(referenceId);

        assertThat(postings).isEmpty();
    }

    private void assertOutboxExists(
            UUID expectedAggregateId,
            UUID expectedReferenceId,
            TransactionStatus expectedStatus,
            TransactionType type) {

        OutboxEvent outbox = outboxRepository.findAll().stream()
                // 1. Find it by the Kafka Partition Key (Account ID)
                .filter(e -> expectedAggregateId.toString().equals(e.getAggregateId()))
                .filter(e -> e.getEventType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionFailure("No outbox event found for aggregateId=" + expectedAggregateId));

        TransactionResultEvent resultEvent;
        try {
            resultEvent = objectMapper.readValue(outbox.getPayload(), TransactionResultEvent.class);
        } catch (Exception _) {
            throw new AssertionFailure("Failed to deserialize outbox payload");
        }

        // 2. Assert the payload matches the actual transaction reference
        assertThat(resultEvent.referenceId()).isEqualTo(expectedReferenceId);
        assertThat(resultEvent.type()).isEqualTo(type);
        assertThat(resultEvent.status()).isEqualTo(expectedStatus);
        assertThat(resultEvent.timestamp()).isNotNull();
    }
}
