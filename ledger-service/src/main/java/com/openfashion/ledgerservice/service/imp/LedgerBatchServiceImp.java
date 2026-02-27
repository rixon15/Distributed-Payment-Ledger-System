package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerBatchServiceImp implements LedgerBatchService {

    private final RedisService redisService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void processBatch(List<PendingTransaction> batch) {
        log.info("Committing batch of {} transaction to Postgres", batch.size());

        Set<UUID> accountIds = new HashSet<>();
        Set<UUID> refIds = new HashSet<>();

        for (PendingTransaction pendingTransaction : batch) {
            accountIds.add(pendingTransaction.debitAccountId());
            accountIds.add(pendingTransaction.creditAccountId());
            refIds.add(pendingTransaction.referenceId());
        }

        List<Account> accounts = accountRepository.findAllByIdOrUserIdIn(accountIds);
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account acc : accounts) {
            accountMap.put(acc.getId(), acc);     // Index by DB ID (for system accounts)
            accountMap.put(acc.getUserId(), acc); // Index by User ID (for user accounts)
        }


        Map<UUID, Transaction> existingTransactionMap = transactionRepository.findAllByReferenceIdIn(refIds)
                .stream().collect(Collectors.toMap(Transaction::getReferenceId, Function.identity()));


        Map<UUID, Transaction> sessionTransactions = new HashMap<>();
        Map<UUID, BigDecimal> balanceChanges = new HashMap<>();
        List<Transaction> transactionsToSave = new ArrayList<>();
        List<OutboxEvent> outboxEventsToSave = new ArrayList<>();
        List<Posting> postingsToSave = new ArrayList<>();


        for (PendingTransaction pendingTransaction : batch) {

            UUID refId = pendingTransaction.referenceId();

            Transaction transaction = existingTransactionMap.getOrDefault(refId, sessionTransactions.get(refId));
            Account debitAccount = accountMap.get(pendingTransaction.debitAccountId());
            Account creditAccount = accountMap.get(pendingTransaction.creditAccountId());

            if (debitAccount == null || creditAccount == null) {
                handleDeadLetter(pendingTransaction, "Account missing");
                continue;
            }

            switch (pendingTransaction.type()) {
                case WITHDRAWAL_RESERVE -> {

                    if (existingTransactionMap.containsKey(refId)) {
                        log.info("Skipping RESERVE for {}. Already exists in DB", refId);
                        continue;
                    }

                    Transaction newTransaction = createBaseTransaction(pendingTransaction, TransactionStatus.PENDING);
                    transactionsToSave.add(newTransaction);
                    sessionTransactions.put(refId, newTransaction);
                    handleReserve(pendingTransaction, newTransaction, debitAccount, creditAccount, postingsToSave, balanceChanges, outboxEventsToSave);
                }

                case WITHDRAWAL_SETTLE ->
                        handleSettle(pendingTransaction, transaction, debitAccount, creditAccount, postingsToSave, balanceChanges, outboxEventsToSave);

                case WITHDRAWAL_RELEASE ->
                        handleRelease(pendingTransaction, transaction, debitAccount, creditAccount, postingsToSave, balanceChanges, outboxEventsToSave);

                default -> // Normal Transfers/Deposits/Fees
                {
                    Transaction newTransaction = createBaseTransaction(pendingTransaction, TransactionStatus.POSTED);
                    transactionsToSave.add(newTransaction);
                    handleStandard(pendingTransaction, newTransaction, debitAccount, creditAccount, postingsToSave, balanceChanges, outboxEventsToSave);
                }
            }
        }

        transactionRepository.saveAll(transactionsToSave);
        outboxRepository.saveAll(outboxEventsToSave);
        postingRepository.saveAll(postingsToSave);

        balanceChanges.forEach(accountRepository::updateBalance);

        redisService.commitFromBuffer(batch);
    }

    public List<UUID> findAlreadyProcessedIds(List<PendingTransaction> batch) {
        Set<UUID> refIds = batch.stream()
                .map(PendingTransaction::referenceId)
                .collect(Collectors.toSet());

        return transactionRepository.findAllByReferenceIdIn(refIds).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.POSTED || tx.getStatus() == TransactionStatus.FAILED)
                .map(Transaction::getReferenceId)
                .toList();
    }

    private void handleDeadLetter(PendingTransaction pt, String reason) {
        log.error("Dead Letter Triggered for {}:{}", pt.referenceId(), reason);

        //Could save to dedicated DLQ table?

        Transaction failedTx = Transaction.builder()
                .referenceId(pt.referenceId())
                .type(pt.type())
                .status(TransactionStatus.FAILED)
                .metadata(objectMapper.writeValueAsString(Map.of("error", reason)))
                .effectiveDate(Instant.ofEpochMilli(pt.timestamp()))
                .build();

        transactionRepository.save(failedTx);
        outboxRepository.save(createOutboxEvent(failedTx, failedTx.getReferenceId().toString(), "TRANSACTION_FAILED"));
    }

    private OutboxEvent createOutboxEvent(Object payload, String referenceId, String type) {
        return OutboxEvent.builder()
                .aggregateId(referenceId)
                .eventType(type)
                .payload(objectMapper.writeValueAsString(payload))
                .status(OutboxStatus.PENDING)
                .build();
    }

    private void handleReserve(PendingTransaction pt, Transaction tx, Account debit, Account credit,
                               List<Posting> posts, Map<UUID, BigDecimal> balances, List<OutboxEvent> events) {
        applyPostings(pt, tx, debit, credit, posts, balances);
        events.add(createOutboxEvent(pt, pt.referenceId().toString(), "FUNDS_RESERVED_SUCCESS"));
    }

    private void handleSettle(PendingTransaction pt, Transaction tx, Account debit, Account credit,
                              List<Posting> posts, Map<UUID, BigDecimal> balances, List<OutboxEvent> events) {
        if (tx == null) {
            log.error("Settle failed: Transaction not found for ref {}", pt.referenceId());
            return;
        }
        tx.setStatus(TransactionStatus.POSTED);
        applyPostings(pt, tx, debit, credit, posts, balances);
        events.add(createOutboxEvent(pt, pt.referenceId().toString(), "WITHDRAWAL_SETTLED"));
    }

    private void handleRelease(PendingTransaction pt, Transaction tx, Account debit, Account credit,
                               List<Posting> posts, Map<UUID, BigDecimal> balances, List<OutboxEvent> events) {
        if (tx == null) return;
        tx.setStatus(TransactionStatus.FAILED);
        tx.setMetadata("Released by system");
        applyPostings(pt, tx, debit, credit, posts, balances);
        events.add(createOutboxEvent(pt, pt.referenceId().toString(), "TRANSACTION_FAILED"));
    }

    private void handleStandard(PendingTransaction pt, Transaction tx, Account debit, Account credit,
                                List<Posting> posts, Map<UUID, BigDecimal> balances, List<OutboxEvent> events) {
        applyPostings(pt, tx, debit, credit, posts, balances);
        events.add(createOutboxEvent(pt, pt.referenceId().toString(), "TRANSACTION_COMPLETED"));
    }

    private void applyPostings(PendingTransaction pt, Transaction tx, Account debit, Account credit, List<Posting> posts,
                               Map<UUID, BigDecimal> balances) {
        posts.add(new Posting(tx, debit, pt.amount(), PostingDirection.DEBIT));
        posts.add(new Posting(tx, credit, pt.amount(), PostingDirection.CREDIT));
        balances.merge(debit.getId(), pt.amount().negate(), BigDecimal::add);
        balances.merge(credit.getId(), pt.amount(), BigDecimal::add);
    }

    private Transaction createBaseTransaction(PendingTransaction pt, TransactionStatus status) {
        return Transaction.builder()
                .referenceId(pt.referenceId())
                .type(pt.type())
                .status(status)
                .effectiveDate(Instant.ofEpochMilli(pt.timestamp()))
                .build();

    }
}
