package com.openfashion.ledgerservice.service.imp;

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


    @PostConstruct
    public void warmRedisCache() {
        log.info("Warming Redis DB Snapshot cache from Postgres");

        List<Account> allAccounts = accountRepository.findAll();
        for (Account acc : allAccounts) {
            redisService.initializeSnapshotIfMissing(acc);
        }

        log.info("Warmed {} accounts into Redis", allAccounts.size());
    }

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

            postings.add(new Posting(tx, debitAcc, req.getAmount(), PostingDirection.DEBIT));
            postings.add(new Posting(tx, creditAcc, req.getAmount(), PostingDirection.CREDIT));

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
    public void persistRejectedNsf(List<TransactionRequest> nsfList) {

        if (nsfList.isEmpty()) {
            return;
        }

        List<OutboxEvent> outboxEvents = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        for (TransactionRequest request : nsfList) {

            Optional<Transaction> existingTransaction = transactionRepository.findByReferenceIdAndType(request.getReferenceId(), request.getType());

            if (existingTransaction.isPresent()) {
                log.warn("Duplicate NSF transaction detected for referenceId: {}, type: {}. Skipping.",
                        request.getReferenceId(), request.getType());
                continue;
            }

            TransactionResultEvent resultEvent = createTransactionResultEvent(
                    request,
                    TransactionStatus.REJECTED_NSF,
                    "NSF",
                    "Transaction rejected due to insufficient funds"
            );

            transactions.add(createTransaction(request, TransactionStatus.REJECTED_NSF));
            outboxEvents.add(createOutboxEvent(request, request.getDebitAccountId(), resultEvent));
        }

        outboxRepository.saveAll(outboxEvents);
        transactionRepository.saveAll(transactions);
    }

    public void processBatch(List<Transaction> transactions, List<Posting> postings, List<OutboxEvent> outboxEvents) {
        int[] result = transactionBatchRepository.upsertTransactions(transactions);

        List<UUID> successfulTransactionIds = new ArrayList<>();

        for (int i = 0; i < result.length; i++) {
            if (result[i] > 0) {
                successfulTransactionIds.add(transactions.get(i).getReferenceId());
            }
        }

        if (successfulTransactionIds.isEmpty()) {
            log.info("Entire batch was already processed. Skipping downstream updates.");
            return;
        }

        List<Posting> filteredPostings = postings.stream()
                .filter(p -> successfulTransactionIds.contains(p.getTransaction().getReferenceId()))
                .toList();
        postingRepository.saveAll(filteredPostings);

        List<OutboxEvent> filteredEvents = outboxEvents.stream()
                .filter(e -> successfulTransactionIds.contains(UUID.fromString(e.getAggregateId())))
                .toList();
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
}