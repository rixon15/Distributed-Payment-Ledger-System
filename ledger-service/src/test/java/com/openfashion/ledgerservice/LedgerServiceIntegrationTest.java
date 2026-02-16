package com.openfashion.ledgerservice;

import com.openfashion.ledgerservice.dto.ReleaseRequest;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalCompleteEvent;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LedgerServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.2"));

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private PostingRepository postingRepository;

    private UUID userId;
    private static final String PENDING_NAME = "PENDING_WITHDRAWAL";
    private static final String WORLD_NAME = "WORLD_LIQUIDITY";

    @BeforeEach
    void cleanDb() {
        postingRepository.deleteAll();
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        userId = UUID.randomUUID();

        // Always setup System Accounts
        setupAccount(null, WORLD_NAME, AccountType.EQUITY, BigDecimal.ZERO);
        setupAccount(null, PENDING_NAME, AccountType.LIABILITY, BigDecimal.ZERO);
        setupAccount(userId, "User Wallet", AccountType.ASSET, new BigDecimal("100.00"));

    }

    @Test
    @DisplayName("End-to-End: Deposit -> Transfer -> Verify DB & Outbox")
    void testFullFlow() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        setupAccount(aliceId, "Alice", AccountType.ASSET, BigDecimal.ZERO);
        setupAccount(bobId, "Bob", AccountType.ASSET, BigDecimal.ZERO);

        TransactionRequest deposit = createRequest(TransactionType.DEPOSIT, aliceId, null, "100.00");
        ledgerService.processTransaction(deposit);

        TransactionRequest transfer = createRequest(TransactionType.TRANSFER, bobId, aliceId, "40.00");
        ledgerService.processTransaction(transfer);

        Account alice = accountRepository.findByUserIdAndCurrency(aliceId, CurrencyType.USD).orElseThrow();
        Account bob = accountRepository.findByUserIdAndCurrency(bobId, CurrencyType.USD).orElseThrow();

        assertThat(alice.getBalance()).isEqualByComparingTo("60.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("40.00");

        assertThat(outboxRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("Race Condition: Prevent Double Spending with Optimistic Locking")
    void testConcurrency() throws InterruptedException {

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        setupAccount(aliceId, "Alice", AccountType.ASSET, new BigDecimal("50.00"));
        setupAccount(bobId, "Bob", AccountType.ASSET, BigDecimal.ZERO);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    TransactionRequest request = createRequest(TransactionType.TRANSFER, bobId, aliceId, "30.00");
                    latch.await();
                    ledgerService.processTransaction(request);
                } catch (Exception _) {/*Catch is here as a placeholder */}
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Account alice = accountRepository.findByUserIdAndCurrency(aliceId, CurrencyType.USD).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("20.00");

        long successTx = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus().toString().equals("POSTED")).count();

        assertThat(successTx).isEqualTo(1);

    }

    @Test
    @DisplayName("Idempotency: Replaying the same request ID should be a No-Op")
    void testIdempotency() {
        UUID aliceId = UUID.randomUUID();
        setupAccount(aliceId, "Alice", AccountType.ASSET, new BigDecimal("100.00"));

        TransactionRequest request = createRequest(TransactionType.WITHDRAWAL, null, aliceId, "10.00");
        request.setReferenceId("REPLAY-TEST-1");

        ledgerService.processTransaction(request);

        ledgerService.processTransaction(request);

        Account alice = accountRepository.findByUserIdAndCurrency(aliceId, CurrencyType.USD).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("Async: Poller should eventually pick up event")
    void testOutboxPollerProcessing() {
        UUID aliceId = UUID.randomUUID();
        setupAccount(aliceId, "Alice", AccountType.ASSET, new BigDecimal("100.00"));

        TransactionRequest req = createRequest(TransactionType.WITHDRAWAL, null, aliceId, "10.00");
        ledgerService.processTransaction(req);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<OutboxEvent> events = outboxRepository.findAll();
                    assertThat(events).isNotEmpty();
                    assertThat(events.getFirst().getStatus().name()).isEqualTo("PROCESSED");
                });
    }

    @Test
    @DisplayName("Full Withdrawal Cycle: Reserve -> Settle")
    void testFullWithdrawalLifecycle() {
        UUID referenceId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("40.00");

        ReservationRequest reservationRequest = new ReservationRequest(userId, amount, CurrencyType.USD, referenceId);
        ledgerService.reserveFunds(reservationRequest);

        Account userAcc = accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD).orElseThrow();
        Account pendingAcc = accountRepository.findByNameAndCurrency(PENDING_NAME, CurrencyType.USD).orElseThrow();

        assertThat(userAcc.getBalance()).isEqualByComparingTo("60.00");
        assertThat(pendingAcc.getBalance()).isEqualByComparingTo("40.00");
        assertThat(transactionRepository.existsByReferenceId(referenceId.toString())).isTrue();

        WithdrawalCompleteEvent settleEvent = new WithdrawalCompleteEvent(referenceId, amount, CurrencyType.USD);
        ledgerService.processWithdrawal(settleEvent);

        Account finalPendingAcc = accountRepository.findByNameAndCurrency(PENDING_NAME, CurrencyType.USD).orElseThrow();
        Account worldAcc = accountRepository.findByNameAndCurrency(WORLD_NAME, CurrencyType.USD).orElseThrow();
        Transaction tx = transactionRepository.findByReferenceId(referenceId.toString()).orElseThrow();

        assertThat(finalPendingAcc.getBalance()).isEqualByComparingTo("0.00");
        assertThat(worldAcc.getBalance()).isEqualByComparingTo("40.00");
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(postingRepository.findAll()).hasSize(4);
    }

    @Test
    @DisplayName("Compensating Transaction: Reserve -> Release")
    void testWithdrawalCompensation() {
        UUID referenceId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("25.00");

        ledgerService.reserveFunds(new ReservationRequest(userId, amount, CurrencyType.USD, referenceId));

        ledgerService.releaseFunds(new ReleaseRequest(referenceId));

        Account userAcc = accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD).orElseThrow();
        Account pendingAcc = accountRepository.findByNameAndCurrency(PENDING_NAME, CurrencyType.USD).orElseThrow();
        Transaction transaction = transactionRepository.findByReferenceId(referenceId.toString()).orElseThrow();

        assertThat(userAcc.getBalance()).isEqualByComparingTo("100.00");
        assertThat(pendingAcc.getBalance()).isEqualByComparingTo("0.00");
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }


    private void setupAccount(UUID userId, String name, AccountType type, BigDecimal balance) {
        Account a = new Account();
        a.setUserId(userId == null ? UUID.randomUUID() : userId);
        a.setName(name);
        a.setType(type);
        a.setBalance(balance);
        a.setCurrency(CurrencyType.USD);
        a.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(a);
    }

    private TransactionRequest createRequest(TransactionType type, UUID receiver, UUID sender, String amount) {
        TransactionRequest r = new TransactionRequest();
        r.setReferenceId(UUID.randomUUID().toString());
        r.setType(type);
        r.setSenderId(sender);
        r.setReceiverId(receiver);
        r.setAmount(new BigDecimal(amount));
        r.setCurrency(CurrencyType.USD);
        return r;
    }
}
