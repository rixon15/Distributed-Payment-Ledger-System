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
        Set<String> refIds = new HashSet<>();

        for (PendingTransaction pendingTransaction : batch) {
            accountIds.add(pendingTransaction.debitAccountId());
            accountIds.add(pendingTransaction.creditAccountId());
            refIds.add(pendingTransaction.referenceId().toString());
        }

        Map<UUID, Account> accountMap = accountRepository.findAllById(accountIds)
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));

        Map<String, Transaction> existingTransactionMap = transactionRepository.findAllByReferenceIdIn(refIds)
                .stream().collect(Collectors.toMap(Transaction::getReferenceId, Function.identity()));


        Map<String, Transaction> sessionTransactions = new HashMap<>();
        Map<UUID, BigDecimal> balanceChanges = new HashMap<>();
        List<Transaction> transactionsToSave = new ArrayList<>();
        List<OutboxEvent> outboxEventsToSave = new ArrayList<>();
        List<Posting> postingsToSave = new ArrayList<>();


        for (PendingTransaction pendingTransaction : batch) {

            String refIdStr = pendingTransaction.referenceId().toString();

            Transaction transaction = existingTransactionMap.getOrDefault(refIdStr, sessionTransactions.get(refIdStr));
            Account debitAccount = accountMap.get(pendingTransaction.debitAccountId());
            Account creditAccount = accountMap.get(pendingTransaction.creditAccountId());

            if (debitAccount == null || creditAccount == null) {
                handleDeadLetter(pendingTransaction, "Account missing");
                continue;
            }

            switch (pendingTransaction.type()) {
                case WITHDRAWAL_RESERVE -> {
                    Transaction newTransaction = createBaseTransaction(pendingTransaction, TransactionStatus.PENDING);
                    transactionsToSave.add(newTransaction);
                    sessionTransactions.put(refIdStr, newTransaction);
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

    private void handleDeadLetter(PendingTransaction pt, String reason) {
        log.error("Dead Letter Triggered for {}:{}", pt.referenceId(), reason);

        //Could save to dedicated DLQ table?

        Transaction failedTx = Transaction.builder()
                .referenceId(pt.referenceId().toString())
                .status(TransactionStatus.FAILED)
                .metadata(objectMapper.writeValueAsString(Map.of("error", reason)))
                .build();

        transactionRepository.save(failedTx);
        outboxRepository.save(createOutboxEvent(failedTx, failedTx.getReferenceId(), "TRANSACTION_FAILED"));
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
        events.add(createOutboxEvent(pt, pt.referenceId().toString(), "WITHDRAWAL_RESERVED"));
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
                .referenceId(pt.referenceId().toString())
                .type(pt.type())
                .status(status)
                .effectiveDate(Instant.ofEpochMilli(pt.timestamp()))
                .build();

    }
}
