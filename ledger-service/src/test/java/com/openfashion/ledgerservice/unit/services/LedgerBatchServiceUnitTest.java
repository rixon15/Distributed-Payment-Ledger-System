package com.openfashion.ledgerservice.unit.services;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.AccountType;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.OutboxEvent;
import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.PostingDirection;
import com.openfashion.ledgerservice.model.Transaction;
import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionBatchRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.RedisService;
import com.openfashion.ledgerservice.service.imp.LedgerBatchServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked"})
class LedgerBatchServiceUnitTest {

    private RedisService redisService;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private PostingRepository postingRepository;
    private OutboxRepository outboxRepository;
    private TransactionBatchRepository transactionBatchRepository;

    private LedgerBatchServiceImp service;

    @BeforeEach
    void setUp() {
        redisService = org.mockito.Mockito.mock(RedisService.class);
        accountRepository = org.mockito.Mockito.mock(AccountRepository.class);
        transactionRepository = org.mockito.Mockito.mock(TransactionRepository.class);
        postingRepository = org.mockito.Mockito.mock(PostingRepository.class);
        outboxRepository = org.mockito.Mockito.mock(OutboxRepository.class);
        transactionBatchRepository = org.mockito.Mockito.mock(TransactionBatchRepository.class);

        service = spy(new LedgerBatchServiceImp(
                redisService,
                accountRepository,
                transactionRepository,
                postingRepository,
                outboxRepository,
                transactionBatchRepository
        ));
    }

    @Test
    void warmRedisCache_shouldInitializeSnapshotForEveryAccount() {
        Account account1 = account("10.0000");
        Account account2 = account("20.0000");

        when(accountRepository.findAll()).thenReturn(List.of(account1, account2));

        service.warmRedisCache();

        verify(accountRepository).findAll();
        verify(redisService).initializeSnapshotIfMissing(account1);
        verify(redisService).initializeSnapshotIfMissing(account2);
    }

    @Test
    void saveTransactions_shouldBuildTransactionsPostingsAndOutboxEventsForValidRequests() {
        Account debit = account("100.0000");
        Account credit = account("0.0000");

        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                debit.getId(),
                credit.getId(),
                "10.0000"
        );

        when(accountRepository.findAllById(anySet())).thenReturn(List.of(debit, credit));
        doNothing().when(service).processBatch(anyList(), anyList(), anyList());

        service.saveTransactions(List.of(request));

        ArgumentCaptor<List<Transaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Posting>> postingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);

        verify(service).processBatch(txCaptor.capture(), postingCaptor.capture(), outboxCaptor.capture());

        List<Transaction> transactions = txCaptor.getValue();
        List<Posting> postings = postingCaptor.getValue();
        List<OutboxEvent> outboxEvents = outboxCaptor.getValue();

        assertThat(transactions).hasSize(1);
        Transaction tx = transactions.getFirst();
        assertThat(tx.getReferenceId()).isEqualTo(request.getReferenceId());
        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(tx.getMetadata()).isNotBlank();

        assertThat(postings).hasSize(2);

        Posting debitPosting = postings.stream()
                .filter(p -> p.getDirection() == PostingDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        Posting creditPosting = postings.stream()
                .filter(p -> p.getDirection() == PostingDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(debitPosting.getTransaction()).isSameAs(tx);
        assertThat(debitPosting.getAccount().getId()).isEqualTo(debit.getId());
        assertThat(debitPosting.getAmount()).isEqualByComparingTo("10.0000");

        assertThat(creditPosting.getTransaction()).isSameAs(tx);
        assertThat(creditPosting.getAccount().getId()).isEqualTo(credit.getId());
        assertThat(creditPosting.getAmount()).isEqualByComparingTo("10.0000");

        assertThat(outboxEvents).hasSize(1);
        OutboxEvent outboxEvent = outboxEvents.getFirst();
        assertThat(outboxEvent.getAggregateId()).isEqualTo(debit.getId().toString());
        assertThat(outboxEvent.getEventType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(outboxEvent.getPayload()).isNotBlank();
    }

    @Test
    void saveTransactions_shouldSkipRequestsWithMissingAccounts() {
        Account debit = account("100.0000");

        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                debit.getId(),
                UUID.randomUUID(),
                "10.0000"
        );

        when(accountRepository.findAllById(anySet())).thenReturn(List.of(debit));
        doNothing().when(service).processBatch(anyList(), anyList(), anyList());

        service.saveTransactions(List.of(request));

        ArgumentCaptor<List<Transaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Posting>> postingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);

        verify(service).processBatch(txCaptor.capture(), postingCaptor.capture(), outboxCaptor.capture());

        assertThat(txCaptor.getValue()).isEmpty();
        assertThat(postingCaptor.getValue()).isEmpty();
        assertThat(outboxCaptor.getValue()).isEmpty();
    }

    @Test
    void persistRejected_shouldReturnImmediatelyForNullOrEmptyInput() {
        service.persistRejected(null, TransactionStatus.REJECTED_NSF);
        service.persistRejected(List.of(), TransactionStatus.REJECTED_NSF);

        verify(transactionRepository, never()).findAllByReferenceIdIn(anySet());
        verify(transactionBatchRepository, never()).upsertTransactions(anyList());
        verify(outboxRepository, never()).saveAll(anyList());
    }

    @Test
    void persistRejected_shouldSkipDuplicateRejectedTransactions() {
        UUID referenceId = UUID.randomUUID();

        TransactionRequest duplicateRequest = request(
                referenceId,
                TransactionType.TRANSFER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "10.0000"
        );
        duplicateRequest.setSenderId(UUID.randomUUID());

        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID())
                .referenceId(referenceId)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.REJECTED_NSF)
                .build();

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of(existing));
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());

        service.persistRejected(List.of(duplicateRequest), TransactionStatus.REJECTED_NSF);

        verify(transactionBatchRepository, never()).upsertTransactions(anyList());
        verify(outboxRepository, never()).saveAll(anyList());
    }

    @Test
    void persistRejected_shouldPersistOnlySuccessfulUpserts() {
        UUID senderId = UUID.randomUUID();

        TransactionRequest req1 = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                null,
                UUID.randomUUID(),
                "10.0000"
        );
        req1.setSenderId(senderId);

        TransactionRequest req2 = request(
                UUID.randomUUID(),
                TransactionType.PAYMENT,
                null,
                UUID.randomUUID(),
                "20.0000"
        );
        req2.setSenderId(senderId);

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1, 0});

        service.persistRejected(List.of(req1, req2), TransactionStatus.REJECTED_NSF);

        ArgumentCaptor<List<Transaction>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(transactionBatchRepository).upsertTransactions(txCaptor.capture());

        List<Transaction> persistedTransactions = txCaptor.getValue();
        assertThat(persistedTransactions).hasSize(2);
        assertThat(persistedTransactions)
                .extracting(Transaction::getStatus)
                .containsExactly(TransactionStatus.REJECTED_NSF, TransactionStatus.REJECTED_NSF);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.getFirst().getEventType()).isEqualTo(req1.getType());
        assertThat(savedEvents.getFirst().getAggregateId()).isEqualTo(senderId.toString());
        assertThat(savedEvents.getFirst().getPayload()).contains("REJECTED_NSF");
        assertThat(savedEvents.getFirst().getPayload()).contains("NSF");
    }

    @Test
    void persistRejected_shouldUseDebitAccountIdAsAggregateKeyWhenPresent() {
        UUID debitAccountId = UUID.randomUUID();

        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                debitAccountId,
                UUID.randomUUID(),
                "10.0000"
        );
        request.setSenderId(UUID.randomUUID());

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1});

        service.persistRejected(List.of(request), TransactionStatus.REJECTED_VALIDATION);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.getFirst().getAggregateId()).isEqualTo(debitAccountId.toString());
        assertThat(savedEvents.getFirst().getPayload()).contains("VALIDATION");
    }

    @Test
    void persistRejected_shouldUseSenderCurrencyMatchedAccountWhenDebitMissing() {
        UUID senderId = UUID.randomUUID();
        Account matchedAccount = account("30.0000");
        matchedAccount.setUserId(senderId);
        matchedAccount.setCurrency(CurrencyType.USD);

        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                null,
                UUID.randomUUID(),
                "10.0000"
        );
        request.setSenderId(senderId);
        request.setCurrency(CurrencyType.USD);

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of(matchedAccount));
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1});

        service.persistRejected(List.of(request), TransactionStatus.REJECTED_NSF);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.getFirst().getAggregateId()).isEqualTo(matchedAccount.getId().toString());
    }

    @Test
    void persistRejected_shouldFallbackToSenderIdWhenNoMatchingAccountExists() {
        UUID senderId = UUID.randomUUID();

        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                null,
                UUID.randomUUID(),
                "10.0000"
        );
        request.setSenderId(senderId);

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1});

        service.persistRejected(List.of(request), TransactionStatus.REJECTED_NSF);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.getFirst().getAggregateId()).isEqualTo(senderId.toString());
    }

    @Test
    void persistRejected_shouldFallbackToReferenceIdWhenOnlyReceiverExists() {
        UUID referenceId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        TransactionRequest request = request(
                referenceId,
                TransactionType.TRANSFER,
                null,
                receiverId,
                "10.0000"
        );
        request.setSenderId(null);

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1});

        service.persistRejected(List.of(request), TransactionStatus.REJECTED_NSF);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.getFirst().getAggregateId()).isEqualTo(referenceId.toString());
    }

    @Test
    void persistRejected_shouldReturnWhenNoTransactionsAreCreatedAfterFiltering() {
        UUID referenceId = UUID.randomUUID();

        TransactionRequest duplicateRequest = request(
                referenceId,
                TransactionType.TRANSFER,
                null,
                UUID.randomUUID(),
                "10.0000"
        );
        duplicateRequest.setSenderId(UUID.randomUUID());

        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID())
                .referenceId(referenceId)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.POSTED)
                .build();

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of(existing));
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());

        service.persistRejected(List.of(duplicateRequest), TransactionStatus.REJECTED_NSF);

        verify(transactionBatchRepository, never()).upsertTransactions(anyList());
        verify(outboxRepository, never()).saveAll(anyList());
    }

    @Test
    void persistRejected_shouldReturnWhenNoUpsertsSucceed() {
        TransactionRequest request = request(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                null,
                UUID.randomUUID(),
                "10.0000"
        );
        request.setSenderId(UUID.randomUUID());

        when(transactionRepository.findAllByReferenceIdIn(anySet())).thenReturn(List.of());
        when(accountRepository.findByUserIdIn(anySet())).thenReturn(List.of());
        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{0});

        service.persistRejected(List.of(request), TransactionStatus.REJECTED_NSF);

        verify(outboxRepository, never()).saveAll(anyList());
    }

    @Test
    void processBatch_shouldReturnWhenEntireBatchAlreadyProcessed() {
        Transaction tx = transaction(UUID.randomUUID(), TransactionType.TRANSFER, TransactionStatus.POSTED);
        Posting posting = posting(tx, account("0.0000"), "10.0000", PostingDirection.DEBIT);
        OutboxEvent event = outboxEvent(tx.getType(), UUID.randomUUID().toString(), "{}");

        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{0});

        service.processBatch(List.of(tx), List.of(posting), List.of(event));

        verify(postingRepository, never()).saveAll(anyList());
        verify(outboxRepository, never()).saveAll(anyList());
        verify(transactionBatchRepository, never()).updateAccountBalances(anyList());
        verify(redisService, never()).syncRedisBalances(any());
    }

    @Test
    void processBatch_shouldFilterBySuccessfulTransactionIndicesAndSyncRedisChanges() {
        UUID ref1 = UUID.randomUUID();
        UUID ref2 = UUID.randomUUID();

        Account acc1 = account("100.0000");
        Account acc2 = account("0.0000");
        Account acc3 = account("0.0000");
        Account acc4 = account("0.0000");

        Transaction tx1 = transaction(ref1, TransactionType.TRANSFER, TransactionStatus.POSTED);
        Transaction tx2 = transaction(ref2, TransactionType.PAYMENT, TransactionStatus.POSTED);

        Posting tx1Debit = posting(tx1, acc1, "10.0000", PostingDirection.DEBIT);
        Posting tx1Credit = posting(tx1, acc2, "10.0000", PostingDirection.CREDIT);
        Posting tx2Debit = posting(tx2, acc3, "20.0000", PostingDirection.DEBIT);
        Posting tx2Credit = posting(tx2, acc4, "20.0000", PostingDirection.CREDIT);

        OutboxEvent event1 = outboxEvent(tx1.getType(), acc1.getId().toString(), "{\"event\":1}");
        OutboxEvent event2 = outboxEvent(tx2.getType(), acc3.getId().toString(), "{\"event\":2}");

        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1, 0});

        service.processBatch(
                List.of(tx1, tx2),
                List.of(tx1Debit, tx1Credit, tx2Debit, tx2Credit),
                List.of(event1, event2)
        );

        ArgumentCaptor<List<Posting>> postingCaptor = ArgumentCaptor.forClass(List.class);
        verify(postingRepository).saveAll(postingCaptor.capture());

        List<Posting> savedPostings = postingCaptor.getValue();
        assertThat(savedPostings).hasSize(2);
        assertThat(savedPostings).extracting(p -> p.getTransaction().getReferenceId())
                .containsOnly(ref1);

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).saveAll(outboxCaptor.capture());

        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).containsExactly(event1);

        verify(transactionBatchRepository).updateAccountBalances(savedPostings);

        ArgumentCaptor<Map<UUID, BigDecimal>> changesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).syncRedisBalances(changesCaptor.capture());

        Map<UUID, BigDecimal> changes = changesCaptor.getValue();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(acc1.getId())).isEqualByComparingTo("-10.0000");
        assertThat(changes.get(acc2.getId())).isEqualByComparingTo("10.0000");
        assertThat(changes).doesNotContainKeys(acc3.getId(), acc4.getId());
    }

    @Test
    void processBatch_shouldAggregateMultiplePostingsPerAccountBeforeSyncingRedis() {
        UUID sharedAccountId = UUID.randomUUID();

        Account sharedDebitAccount = accountWithId(sharedAccountId, "100.0000");
        Account creditA = account("0.0000");
        Account creditB = account("0.0000");

        Transaction tx1 = transaction(UUID.randomUUID(), TransactionType.TRANSFER, TransactionStatus.POSTED);
        Transaction tx2 = transaction(UUID.randomUUID(), TransactionType.PAYMENT, TransactionStatus.POSTED);

        Posting tx1Debit = posting(tx1, sharedDebitAccount, "10.0000", PostingDirection.DEBIT);
        Posting tx1Credit = posting(tx1, creditA, "10.0000", PostingDirection.CREDIT);
        Posting tx2Debit = posting(tx2, sharedDebitAccount, "5.0000", PostingDirection.DEBIT);
        Posting tx2Credit = posting(tx2, creditB, "5.0000", PostingDirection.CREDIT);

        when(transactionBatchRepository.upsertTransactions(anyList())).thenReturn(new int[]{1, 1});

        service.processBatch(
                List.of(tx1, tx2),
                List.of(tx1Debit, tx1Credit, tx2Debit, tx2Credit),
                List.of(
                        outboxEvent(tx1.getType(), sharedDebitAccount.getId().toString(), "{}"),
                        outboxEvent(tx2.getType(), sharedDebitAccount.getId().toString(), "{}")
                )
        );

        ArgumentCaptor<Map<UUID, BigDecimal>> changesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).syncRedisBalances(changesCaptor.capture());

        Map<UUID, BigDecimal> changes = changesCaptor.getValue();
        assertThat(changes.get(sharedDebitAccount.getId())).isEqualByComparingTo("-15.0000");
        assertThat(changes.get(creditA.getId())).isEqualByComparingTo("10.0000");
        assertThat(changes.get(creditB.getId())).isEqualByComparingTo("5.0000");
    }

    private TransactionRequest request(
            UUID referenceId,
            TransactionType type,
            UUID debitAccountId,
            UUID creditAccountId,
            String amount
    ) {
        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(referenceId);
        request.setType(type);
        request.setAmount(new BigDecimal(amount));
        request.setCurrency(CurrencyType.USD);
        request.setDebitAccountId(debitAccountId);
        request.setCreditAccountId(creditAccountId);
        request.setSenderId(UUID.randomUUID());
        request.setReceiverId(UUID.randomUUID());
        request.setMetadata("meta");
        return request;
    }

    private Account account(String balance) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(UUID.randomUUID());
        account.setName("acct-" + UUID.randomUUID());
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    private Account accountWithId(UUID id, String balance) {
        Account account = account(balance);
        account.setId(id);
        return account;
    }

    private Transaction transaction(UUID referenceId, TransactionType type, TransactionStatus status) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .referenceId(referenceId)
                .type(type)
                .status(status)
                .metadata("{}")
                .createdAt(java.time.Instant.now())
                .effectiveDate(java.time.Instant.now())
                .build();
    }

    private Posting posting(Transaction transaction, Account account, String amount, PostingDirection direction) {
        return new Posting(transaction, account, new BigDecimal(amount), direction);
    }

    private OutboxEvent outboxEvent(TransactionType type, String aggregateId, String payload) {
        return OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(type)
                .payload(payload)
                .createdAt(java.time.Instant.now())
                .build();
    }
}