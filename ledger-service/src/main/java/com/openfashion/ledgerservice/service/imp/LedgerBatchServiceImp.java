package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionResultEvent;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
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
        Set<UUID> referenceIds = batch.stream()
                .map(TransactionRequest::getReferenceId)
                .collect(Collectors.toSet());

        List<Transaction> existingTransactions = transactionRepository.findAllByReferenceIdIn(referenceIds);

        Set<String> existingKeys = existingTransactions.stream()
                .map(tx -> tx.getReferenceId() + "-" + tx.getType().name())
                .collect(Collectors.toSet());

        List<TransactionRequest> newRequests = batch.stream()
                .filter(req -> !existingKeys.contains(req.getReferenceId() + "-" + req.getType().name()))
                .toList();

        if (newRequests.isEmpty()) {
            log.info("All {} transactions in batch already exists in DB. Skipping inserts", batch.size());
            return;
        }

        Set<UUID> accountIds = new HashSet<>();
        for (TransactionRequest req : newRequests) {
            accountIds.add(req.getDebitAccountId());
            accountIds.add(req.getCreditAccountId());
        }

        Map<UUID, Account> accountMap = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, acc -> acc));

        List<Transaction> transactions = new ArrayList<>();
        List<Posting> postings = new ArrayList<>();
        List<OutboxEvent> outboxEvents = new ArrayList<>();

        Map<UUID, BigDecimal> netChanges = new HashMap<>();

        for (TransactionRequest req : newRequests) {
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

            netChanges.merge(debitAcc.getId(), req.getAmount().negate(), BigDecimal::add);
            netChanges.merge(creditAcc.getId(), req.getAmount(), BigDecimal::add);

            // Build Outbox Event (Keyed by Sender ID for downstream Parallel Consumer ordering)
            TransactionResultEvent resultEvent = createTransactionResultEvent(
                    req,
                    TransactionStatus.POSTED,
                    "SUCCESS",
                    "Transaction posted successfully"
            );

            outboxEvents.add(createOutboxEvent(req, debitAcc.getId(), resultEvent));
        }

        for (Map.Entry<UUID, BigDecimal> entry : netChanges.entrySet()) {
            Account acc = accountMap.get(entry.getKey());

            acc.setBalance(acc.getBalance().add(entry.getValue()));
        }

        transactionRepository.saveAll(transactions);
        postingRepository.saveAll(postings);
        outboxRepository.saveAll(outboxEvents);
        accountRepository.saveAll(accountMap.values());

        log.info("Persisted batch of {} transactions to Postgres.", newRequests.size());
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
                .referenceId(request.getReferenceId())
                .type(request.getType())
                .status(status)
                .effectiveDate(Instant.now())
                .metadata(serialize(request))
                .build();
    }
}