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
        Map<UUID, BigDecimal> balanceChanges = new HashMap<>();
        List<Transaction> transactionsToSave = new ArrayList<>();
        List<OutboxEvent> outboxEventsToSave = new ArrayList<>();
        List<Posting> postingsToSave = new ArrayList<>();

        for (PendingTransaction pendingTransaction : batch) {
            accountIds.add(pendingTransaction.debitAccountId());
            accountIds.add(pendingTransaction.creditAccountId());
        }

        Map<UUID, Account> accountMap = accountRepository.findAllById(accountIds)
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));

        for (PendingTransaction pendingTransaction : batch) {
            Transaction transaction = Transaction.builder()
                    .referenceId(pendingTransaction.referenceId().toString())
                    .type(pendingTransaction.type())
                    .status(TransactionStatus.POSTED)
                    .effectiveDate(Instant.ofEpochMilli(pendingTransaction.timestamp()))
                    .build();


            Account debitAccount = accountMap.get(pendingTransaction.debitAccountId());
            Account creditAccount = accountMap.get(pendingTransaction.creditAccountId());

            if (debitAccount == null || creditAccount == null) {
                log.error("Critical: Account missing from transaction {}", pendingTransaction.referenceId());
                handleDeadLetter(pendingTransaction, "Account misssing during batch processing");
                continue;
            }

            transactionsToSave.add(transaction);

            postingsToSave.add(new Posting(pendingTransaction.referenceId(), transaction, debitAccount, pendingTransaction.amount(), PostingDirection.DEBIT));
            postingsToSave.add(new Posting(pendingTransaction.referenceId(), transaction, creditAccount, pendingTransaction.amount(), PostingDirection.CREDIT));

            balanceChanges.merge(pendingTransaction.debitAccountId(), pendingTransaction.amount().negate(), BigDecimal::add);
            balanceChanges.merge(pendingTransaction.creditAccountId(), pendingTransaction.amount(), BigDecimal::add);


            outboxEventsToSave.add(createOutboxEvent(pendingTransaction, pendingTransaction.referenceId().toString(), "TRANSACTION_COMPLETED"));
        }

        transactionRepository.saveAll(transactionsToSave);
        outboxRepository.saveAll(outboxEventsToSave);
        postingRepository.saveAll(postingsToSave);

        for (Map.Entry<UUID, BigDecimal> entry : balanceChanges.entrySet()) {
            accountRepository.updateBalance(entry.getKey(), entry.getValue());
        }

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
}
