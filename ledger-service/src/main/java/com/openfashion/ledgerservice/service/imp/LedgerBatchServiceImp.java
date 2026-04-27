package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionResultEvent;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.*;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * Batch persistence implementation for ledger posting, outbox emission, and balance sync.
 *
 * <p>This service:
 * <ul>
 *   <li>warms Redis snapshots from Postgres on startup,</li>
 *   <li>persists transactions/postings/outbox events in batch,</li>
 *   <li>updates account balances in Postgres,</li>
 *   <li>reconciles confirmed balance deltas back into Redis.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerBatchServiceImp implements LedgerBatchService {

    private final RedisService redisService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionBatchRepository transactionBatchRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();


    /**
     * Warms Redis account snapshot cache from current Postgres accounts.
     */
    @PostConstruct
    public void warmRedisCache() {
        log.info("Warming Redis DB Snapshot cache from Postgres");

        List<Account> allAccounts = accountRepository.findAll();
        for (Account acc : allAccounts) {
            redisService.initializeSnapshotIfMissing(acc);
        }

        log.info("Warmed {} accounts into Redis", allAccounts.size());
    }

    /**
     * Persists accepted ledger requests as posted transactions, postings, and outbox result events.
     *
     * <p>Requests missing debit/credit accounts are skipped and logged as data mismatch candidates.
     *
     * @param batch accepted requests from Redis staging
     */
    @Override
    @Transactional
    public void saveTransactions(List<TransactionRequest> batch) {
        Set<UUID> accountIds = new HashSet<>();
        for (TransactionRequest req : batch) {
            accountIds.add(req.getDebitAccountId());
            accountIds.add(req.getCreditAccountId());
        }

        Map<UUID, Account> accountMap = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, acc -> acc));

        List<Transaction> transactions = new ArrayList<>();
        List<Posting> postings = new ArrayList<>();
        List<OutboxEvent> outboxEvents = new ArrayList<>();

        for (TransactionRequest req : batch) {
            Account debitAcc = accountMap.get(req.getDebitAccountId());
            Account creditAcc = accountMap.get(req.getCreditAccountId());

            if (debitAcc == null || creditAcc == null) {
                log.error("CRITICAL: Account missing for transaction {}. DB/Redis out of sync.", req.getReferenceId());
                continue; // In reality, we'd route this to a manual review DLQ
            }

            Transaction tx = createTransaction(req, TransactionStatus.POSTED);

            transactions.add(tx);

            BigDecimal normalizedAmount = MoneyUtil.format(req.getAmount());

            postings.add(new Posting(tx, debitAcc, normalizedAmount, PostingDirection.DEBIT));
            postings.add(new Posting(tx, creditAcc, normalizedAmount, PostingDirection.CREDIT));

            TransactionResultEvent resultEvent = createTransactionResultEvent(
                    req,
                    TransactionStatus.POSTED,
                    "SUCCESS",
                    "Transaction posted successfully"
            );

            outboxEvents.add(createOutboxEvent(req, debitAcc.getId(), resultEvent));
        }

        processBatch(transactions, postings, outboxEvents);


        log.info("Persisted batch of {} transactions to Postgres.", batch.size());
    }

    @Override
    @Transactional
    public void persistRejected(List<TransactionRequest> rejectedList, TransactionStatus reason) {

        if (rejectedList == null || rejectedList.isEmpty()) {
            return;
        }

        Set<UUID> referenceIds = rejectedList.stream()
                .map(TransactionRequest::getReferenceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> existingTxSignatures = transactionRepository.findAllByReferenceIdIn(referenceIds).stream()
                .map(tx -> tx.getReferenceId().toString() + "_" + tx.getType().name())
                .collect(Collectors.toSet());

        Set<UUID> senderIds = rejectedList.stream()
                .map(TransactionRequest::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Account> accountList = senderIds.isEmpty() ? List.of() : accountRepository.findByUserIdIn(senderIds);


        List<OutboxEvent> outboxEvents = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        for (TransactionRequest request : rejectedList) {

            String signature = request.getReferenceId().toString() + "_" + request.getType().name();

            if (existingTxSignatures.contains(signature)) {
                log.warn("Duplicate REJECTED transaction detected for referenceId: {}, type: {}. Skipping.",
                        request.getReferenceId(), request.getType());

                continue;
            }

            TransactionResultEvent resultEvent = createTransactionResultEvent(
                    request, reason, decideReasonCode(reason), decideMessage(reason)
            );

            UUID aggregateKey = resolveSafeAggregateKey(request, accountList);

            transactions.add(createTransaction(request, reason));
            outboxEvents.add(createOutboxEvent(request, aggregateKey, resultEvent));

        }

        if (transactions.isEmpty()) {
            return;
        }

        int[] upsertResult = transactionBatchRepository.upsertTransactions(transactions);

        List<Integer> successfulIndices = IntStream.range(0, upsertResult.length)
                .filter(i -> upsertResult[i] > 0)
                .boxed()
                .toList();

        if (successfulIndices.isEmpty()) {
            return;
        }

        List<OutboxEvent> insertedOutboxEvents = successfulIndices.stream()
                .map(outboxEvents::get)
                .toList();

        outboxRepository.saveAll(insertedOutboxEvents);
    }

    /**
     * Applies idempotent batch persistence flow:
     * upsert transactions, keep only newly inserted indices, persist dependent postings/outbox events,
     * update DB balances, then sync confirmed net changes to Redis.
     *
     * @param transactions candidate transactions for upsert
     * @param postings postings aligned to the transaction list
     * @param outboxEvents response events aligned to the transaction list
     */
    public void processBatch(List<Transaction> transactions, List<Posting> postings, List<OutboxEvent> outboxEvents) {

        int[] upsertResult = transactionBatchRepository.upsertTransactions(transactions);

        // Collect positions (indices), not values.
        List<Integer> successfulIndices = IntStream.range(0, upsertResult.length)
                .filter(i -> upsertResult[i] > 0)
                .boxed()
                .toList();


        if (successfulIndices.isEmpty()) {
            log.info("Entire batch was already processed. Skipping downstream updates.");
            return;
        }

        Set<UUID> successfulReferenceIds = successfulIndices.stream()
                .map(i -> transactions.get(i).getReferenceId())
                .collect(Collectors.toSet());

        List<Posting> filteredPostings = postings.stream()
                .filter(p -> successfulReferenceIds.contains(p.getTransaction().getReferenceId()))
                .toList();
        postingRepository.saveAll(filteredPostings);

        List<OutboxEvent> filteredEvents = successfulIndices.stream()
                .filter(i -> i >= 0 && i < outboxEvents.size())
                .map(outboxEvents::get)
                .toList();

        log.info("Saving {} outbox events for {} successful transactions", filteredEvents.size(), successfulIndices.size());
        outboxRepository.saveAll(filteredEvents);

        transactionBatchRepository.updateAccountBalances(filteredPostings);

        Map<UUID, BigDecimal> confirmedChanges = filteredPostings.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getAccount().getId(),
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                p -> p.getDirection() == PostingDirection.CREDIT ? p.getAmount() : p.getAmount().negate(),
                                BigDecimal::add
                        )
                ));

        redisService.syncRedisBalances(confirmedChanges);
    }

    private OutboxEvent createOutboxEvent(TransactionRequest req, UUID aggregateKey, TransactionResultEvent resultEvent) {
        return OutboxEvent.builder()
                .aggregateId(aggregateKey.toString()) // Critical for Debezium Kafka Key
                .eventType(req.getType())
                .payload(serialize(resultEvent))
                .createdAt(Instant.now())
                .build();
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception _) {
            return "{}";
        }
    }

    private TransactionResultEvent createTransactionResultEvent(TransactionRequest req, TransactionStatus status, String reasonCode, String message) {
        return new TransactionResultEvent(
                req.getReferenceId(),
                req.getType(),
                status,
                reasonCode,
                message,
                Instant.now()
        );
    }

    private Transaction createTransaction(TransactionRequest request, TransactionStatus status) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .referenceId(request.getReferenceId())
                .type(request.getType())
                .status(status)
                .effectiveDate(Instant.now())
                .metadata(serialize(request))
                .createdAt(Instant.now())
                .build();
    }

    private String decideReasonCode(TransactionStatus status) {
        switch (status) {
            case REJECTED_NSF -> {
                return "NSF";
            }
            case REJECTED_VALIDATION -> {
                return "VALIDATION";
            }
            case null, default -> {
                return "UNKNOWN";
            }
        }
    }

    private String decideMessage(TransactionStatus status) {
        switch (status) {
            case REJECTED_NSF -> {
                return "Transaction rejected due to insufficient funds";
            }
            case REJECTED_VALIDATION -> {
                return "Transaction rejected due to business violation";
            }
            case null, default -> {
                return "Unknown error";
            }
        }
    }

    private UUID resolveSafeAggregateKey(TransactionRequest request, List<Account> accountList) {

        if (request.getDebitAccountId() != null) {
            return request.getDebitAccountId();
        }

        if (request.getSenderId() != null && request.getCurrency() != null) {
            Optional<UUID> matchedAccountId = accountList.stream()
                    .filter(a -> a.getUserId().equals(request.getSenderId()) && a.getCurrency() == request.getCurrency())
                    .map(Account::getId)
                    .findFirst();

            if (matchedAccountId.isPresent()) {
                return matchedAccountId.get();
            }
        }

        if (request.getSenderId() != null) {
            return request.getSenderId();
        }

        return request.getReceiverId() != null ? request.getReferenceId() : UUID.randomUUID();
    }
}