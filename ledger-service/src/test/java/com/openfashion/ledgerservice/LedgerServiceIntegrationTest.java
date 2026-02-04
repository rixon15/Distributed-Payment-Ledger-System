package com.openfashion.ledgerservice;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerService;
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
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @BeforeEach
    void cleanDb() {
        postingRepository.deleteAll();
        accountRepository.deleteAll();
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();

        // Always setup a System Account for Deposit tests
        setupAccount(null, "WORLD_LIQUIDITY", AccountType.EQUITY, BigDecimal.ZERO);
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
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    TransactionRequest request = createRequest(TransactionType.TRANSFER, bobId, aliceId, "30.00");
                    latch.await();
                    ledgerService.processTransaction(request);
                    successCount.incrementAndGet();
                } catch (Exception _) {
                    failureCount.incrementAndGet();
                }
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


    private void setupAccount(UUID userId, String name, AccountType type, BigDecimal balance) {
        Account a = new Account();
        a.setUserId(userId == null ? UUID.randomUUID() : userId);
        a.setName(name);
        a.setType(type);
        a.setBalance(balance);
        a.setCurrency(CurrencyType.USD);
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
