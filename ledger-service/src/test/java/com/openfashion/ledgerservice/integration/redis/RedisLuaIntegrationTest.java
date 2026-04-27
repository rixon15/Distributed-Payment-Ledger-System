package com.openfashion.ledgerservice.integration.redis;

import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.scheduler.RedisProcessor;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisLuaIntegrationTest extends AbstractIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String STREAM_KEY = "ledger:stream:tx";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";

    @Autowired
    private RedisService redisService;
    @Autowired
    private LedgerBatchService ledgerBatchService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private RedisProcessor redisProcessor;

    @Autowired
    private ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE postings, outbox_events, transactions, accounts CASCADE"
        );
        //mommentan commented out, the clean up is not needed at the current time.
//        redisTemplate.delete(List.of(
//                IDEMPOTENCY_KEY,
//                DB_SNAPSHOT_KEY,
//                PENDING_DELTA_KEY,
//                STREAM_KEY,
//                DLQ_STREAM_KEY,
//                BATCH_DONE_STREAM
//        ));
    }

    @PreDestroy
    public void stopConsuming() {
        if (parallelConsumer != null) {
            parallelConsumer.close();
        }
    }

    @Test
    void idempotency_sequentialExactDuplicate_onlyFirstApplied() {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        Account receiver = createUserAccount(UUID.randomUUID(), "RECEIVER", "0.0000");

        UUID referenceId = UUID.randomUUID();
        TransactionRequest request = transfer(referenceId, sender.getId(), receiver.getId(), "50.0000");

        Map<String, List<TransactionRequest>> first = redisService.processBatchAtomic(
                List.of(request), UUID.randomUUID().toString()
        );

        applyResultsToDb(first);

        Map<String, List<TransactionRequest>> second = redisService.processBatchAtomic(
                List.of(request), UUID.randomUUID().toString()
        );

        applyResultsToDb(second);

        assertThat(first.get("ok")).hasSize(1);
        assertThat(second.get("ok")).isEmpty();
        assertThat(second.get("nsf")).isEmpty();

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ? AND type = 'TRANSFER'",
                Integer.class,
                referenceId
        );
        assertThat(txCount).isEqualTo(1);

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("50.0000");
        assertThat(readBalance(receiver.getId())).isEqualByComparingTo("50.0000");
    }

    @Test
    void idempotency_concurrentRace_tenThreads_onlyOneOk() throws Exception {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        Account receiver = createUserAccount(UUID.randomUUID(), "RECEIVER", "0.0000");

        UUID referenceId = UUID.randomUUID();
        TransactionRequest request = transfer(referenceId, sender.getId(), receiver.getId(), "50.0000");

        int threads = 10;

        List<TransactionRequest> allOk = new ArrayList<>();
        List<TransactionRequest> allNsf = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Map<String, List<TransactionRequest>>>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    boolean isReady = start.await(5, TimeUnit.SECONDS);
                    assertThat(isReady).as("Threads failed to reach the 'ready' state within timeout").isTrue();

                    return redisService.processBatchAtomic(List.of(request), UUID.randomUUID().toString());
                }));
            }

            ready.await();
            start.countDown();


            for (Future<Map<String, List<TransactionRequest>>> future : futures) {
                Map<String, List<TransactionRequest>> result = future.get(10, TimeUnit.SECONDS);
                allOk.addAll(result.getOrDefault("ok", List.of()));
                allNsf.addAll(result.getOrDefault("nsf", List.of()));
            }

            pool.shutdownNow();
        }


        assertThat(allOk).hasSize(1);
        assertThat(allNsf).isEmpty();

        applyResultsToDb(Map.of("ok", allOk, "nsf", allNsf));

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ? AND type = 'TRANSFER'",
                Integer.class,
                referenceId
        );
        assertThat(txCount).isEqualTo(1);

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("50.0000");
        assertThat(readBalance(receiver.getId())).isEqualByComparingTo("50.0000");
    }

    @Test
    void softBalanceConcurrency_doubleSpendAttempt_oneOk_oneNsf() throws Exception {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        Account receiverA = createUserAccount(UUID.randomUUID(), "RECEIVER_A", "0.0000");
        Account receiverB = createUserAccount(UUID.randomUUID(), "RECEIVER_B", "0.0000");

        TransactionRequest req1 = transfer(UUID.randomUUID(), sender.getId(), receiverA.getId(), "100.0000");
        TransactionRequest req2 = transfer(UUID.randomUUID(), sender.getId(), receiverB.getId(), "100.0000");

        Map<String, List<TransactionRequest>> r1;
        Map<String, List<TransactionRequest>> r2;

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<Map<String, List<TransactionRequest>>> f1 = pool.submit(() -> {
                ready.countDown();
                boolean isReady = start.await(5, TimeUnit.SECONDS);
                assertThat(isReady).as("Threads failed to reach the 'ready' state within timeout").isTrue();

                return redisService.processBatchAtomic(List.of(req1), UUID.randomUUID().toString());
            });
            Future<Map<String, List<TransactionRequest>>> f2 = pool.submit(() -> {
                ready.countDown();
                boolean isReady = start.await(5, TimeUnit.SECONDS);
                assertThat(isReady).as("Threads failed to reach the 'ready' state within timeout").isTrue();

                return redisService.processBatchAtomic(List.of(req2), UUID.randomUUID().toString());
            });

            boolean isReady = ready.await(5, TimeUnit.SECONDS);
            assertThat(isReady).as("Threads failed to reach the 'ready' state within timeout").isTrue();

            start.countDown();

            r1 = f1.get(10, TimeUnit.SECONDS);
            r2 = f2.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }


        List<TransactionRequest> ok = new ArrayList<>();
        List<TransactionRequest> nsf = new ArrayList<>();
        ok.addAll(r1.getOrDefault("ok", List.of()));
        ok.addAll(r2.getOrDefault("ok", List.of()));
        nsf.addAll(r1.getOrDefault("nsf", List.of()));
        nsf.addAll(r2.getOrDefault("nsf", List.of()));

        assertThat(ok).hasSize(1);
        assertThat(nsf).hasSize(1);

        applyResultsToDb(Map.of("ok", ok, "nsf", nsf));

        Integer postedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'POSTED' AND type = 'TRANSFER'",
                Integer.class
        );
        Integer nsfCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'REJECTED_NSF' AND type = 'TRANSFER'",
                Integer.class
        );

        assertThat(postedCount).isEqualTo(1);
        assertThat(nsfCount).isEqualTo(1);

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("0.0000");

        BigDecimal receiverATotal = readBalance(receiverA.getId());
        BigDecimal receiverBTotal = readBalance(receiverB.getId());
        assertThat(receiverATotal.add(receiverBTotal)).isEqualByComparingTo("100.0000");
    }

    @Test
    void maxBatchSize_500Requests_allProcessedAndPersisted() {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "1000.0000");

        List<TransactionRequest> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Account receiver = createUserAccount(UUID.randomUUID(), "RECEIVER_" + i, "0.0000");
            batch.add(transfer(UUID.randomUUID(), sender.getId(), receiver.getId(), "1.0000"));
        }

        Map<String, List<TransactionRequest>> result = redisService.processBatchAtomic(batch, UUID.randomUUID().toString());

        assertThat(result.getOrDefault("ok", List.of())).hasSize(500);
        assertThat(result.getOrDefault("nsf", List.of())).isEmpty();

        applyResultsToDb(result);

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE type = 'TRANSFER' AND status = 'POSTED' ",
                Integer.class
        );

        assertThat(txCount).isEqualTo(500);

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("500.0000");
    }

    private void applyResultsToDb(Map<String, List<TransactionRequest>> results) {
        List<TransactionRequest> ok = results.getOrDefault("ok", List.of());
        List<TransactionRequest> nsf = results.getOrDefault("nsf", List.of());

        if (!nsf.isEmpty()) {
            ledgerBatchService.persistRejected(nsf, TransactionStatus.REJECTED_NSF);
        }

        if (!ok.isEmpty()) {
            ledgerBatchService.saveTransactions(ok);
        }
    }

    private TransactionRequest transfer(UUID referenceId, UUID debitAccountId, UUID creditAccountId, String amount) {
        TransactionRequest req = new TransactionRequest();
        req.setReferenceId(referenceId);
        req.setType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency(CurrencyType.USD);
        req.setDebitAccountId(debitAccountId);
        req.setCreditAccountId(creditAccountId);
        req.setSenderId(UUID.randomUUID());
        req.setReceiverId(UUID.randomUUID());
        return req;
    }

    private Account createUserAccount(UUID userId, String name, String balance) {
        return accountRepository.findById(userId)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setUserId(userId);
                    account.setName(name);
                    account.setType(AccountType.ASSET);
                    account.setCurrency(CurrencyType.USD);
                    account.setBalance(new BigDecimal(balance));
                    account.setStatus(AccountStatus.ACTIVE);

                    Account saved = accountRepository.saveAndFlush(account);
                    redisService.initializeSnapshotIfMissing(saved);
                    return saved;
                });
    }

    private BigDecimal readBalance(UUID accountId) {
        return MoneyUtil.format(
                jdbcTemplate.queryForObject(
                        "SELECT balance FROM accounts WHERE id = ?",
                        BigDecimal.class,
                        accountId
                )
        );
    }

}
