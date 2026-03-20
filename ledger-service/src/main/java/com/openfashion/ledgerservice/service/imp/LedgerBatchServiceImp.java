package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.dto.TransactionRequest;
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
    public void saveTransactions(List<TransactionRequest> batch) {
        Set<UUID> referenceIds = batch.stream()
                .map(TransactionRequest::getReferenceId)
                .collect(Collectors.toSet());

        Set<UUID> existingRefs = transactionRepository.findAllByReferenceIdIn(referenceIds)
                .stream()
                .map(Transaction::getReferenceId)
                .collect(Collectors.toSet());

        List<TransactionRequest> newRequests = batch.stream()
                .filter(req -> !existingRefs.contains(req.getReferenceId()))
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

            Transaction tx = Transaction.builder()
                    .referenceId(req.getReferenceId())
                    .type(req.getType())
                    .status(TransactionStatus.POSTED)
                    .effectiveDate(Instant.now())
                    .metadata(serialize(req))
                    .build();

            transactions.add(tx);

            postings.add(new Posting(tx, debitAcc, req.getAmount(), PostingDirection.DEBIT));
            postings.add(new Posting(tx, creditAcc, req.getAmount(), PostingDirection.CREDIT));

            netChanges.merge(debitAcc.getId(), req.getAmount().negate(), BigDecimal::add);
            netChanges.merge(creditAcc.getId(), req.getAmount(), BigDecimal::add);

            // Build Outbox Event (Keyed by Sender ID for downstream Parallel Consumer ordering)
            outboxEvents.add(createOutboxEvent(req, debitAcc.getId()));
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

    private OutboxEvent createOutboxEvent(TransactionRequest req, UUID aggregateKey) {
        return OutboxEvent.builder()
                .aggregateId(aggregateKey.toString()) // Critical for Debezium Kafka Key
                .eventType("transaction.posted")
                .payload(serialize(req))
                .status(OutboxStatus.PENDING) // Debezium uses this
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

}