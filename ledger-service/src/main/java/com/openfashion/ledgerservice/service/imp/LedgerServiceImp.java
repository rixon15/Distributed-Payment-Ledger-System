package com.openfashion.ledgerservice.service.imp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.core.exceptions.UnsupportedTransactionException;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.OutboxEvent;
import com.openfashion.ledgerservice.dto.OutboxStatus;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerServiceImp implements LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxRepository outboxRepository;
    private static final String failedTransaction = "transaction.failed";


    @Transactional(isolation = Isolation.SERIALIZABLE)
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2))
    public void processTransaction(TransactionRequest request) {

        validateRequest(request);

        long start = System.currentTimeMillis();
        log.info("Processing transaction with referencId: {}", request.getReferenceId());

        if (transactionRepository.existsByReferenceId(request.getReferenceId())) {
            log.warn("Idempotency Triggered: Transaction {} already processed.", request.getReferenceId());
            return;
        }

        Account debitAccount;
        Account creditAccount;

        //Could implement a strategy pattern for this logic?
        switch (request.getType()) {
            case DEPOSIT -> {
                debitAccount = getSystemAccount("WORLD_LIQUIDITY", request.getCurrency());
                creditAccount = getAccount(request.getReceiverId(), request.getCurrency());
            }
            case WITHDRAWAL -> {
                debitAccount = getAccount(request.getSenderId(), request.getCurrency());
                creditAccount = getSystemAccount("WORLD_LIQUIDITY", request.getCurrency());
            }
            case TRANSFER, PAYMENT, ADJUSTMENT, REFUND -> {
                debitAccount = getAccount(request.getSenderId(), request.getCurrency());
                creditAccount = getAccount(request.getReceiverId(), request.getCurrency());
            }
            case FEE -> {
                debitAccount = getAccount(request.getSenderId(), request.getCurrency());
                creditAccount = getSystemAccount("REVENUE_ACCOUNT", request.getCurrency());
            }
            case INTEREST -> {
                debitAccount = getSystemAccount("INTEREST_EXPENSE", request.getCurrency());
                creditAccount = getAccount(request.getReceiverId(), request.getCurrency());
            }
            default -> throw new UnsupportedTransactionException(String.valueOf(request.getType()));
        }

        Transaction transaction = Transaction.builder()
                .referenceId(request.getReferenceId())
                .type(request.getType())
                .status(TransactionStatus.PENDING)
                .effectiveDate(Instant.now())
                .metadata(request.getMetadata())
                .build();

        if (debitAccount.getStatus() != AccountStatus.ACTIVE || creditAccount.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Transaction {} rejected: Account inactive", request.getReferenceId());

            transaction.setStatus(TransactionStatus.REJECTED_INACTIVE);
            transactionRepository.save(transaction);

            saveOutboxEvent(transaction, failedTransaction);
            return;
        }

        // We ONLY enforce NSF checks on User ASSET accounts.
        // - System Accounts (EQUITY, INCOME, EXPENSE) are unbounded by design (e.g. World Liquidity must go negative to mint money).
        // - LIABILITY accounts (Credit Cards) would require a different check (Credit Limit), not a Zero-Floor check.
        if (debitAccount.getType() == AccountType.ASSET && debitAccount.getBalance().compareTo(request.getAmount()) < 0) {
            transaction.setStatus(TransactionStatus.REJECTED_NSF);
            transactionRepository.save(transaction);
            log.info("Transaction rejected (NSF): {}", request.getReferenceId());

            saveOutboxEvent(transaction, failedTransaction);
            return;
        }

        List<Posting> postings = new ArrayList<>();

        postings.add(createPosting(transaction, debitAccount, request.getAmount(), PostingDirection.DEBIT));
        updateBalance(debitAccount, request.getAmount().negate());

        postings.add(createPosting(transaction, creditAccount, request.getAmount(), PostingDirection.CREDIT));
        updateBalance(creditAccount, request.getAmount());

        transaction.setStatus(TransactionStatus.POSTED);

        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);
        transactionRepository.save(transaction);
        postingRepository.saveAll(postings);

        saveOutboxEvent(transaction, failedTransaction);

        log.info("Transaction {} processed successfully in {} ms", request.getReferenceId(), System.currentTimeMillis() - start);

    }

    private void updateBalance(Account account, BigDecimal amount) {
        account.setBalance(account.getBalance().add(MoneyUtil.format(amount)));
    }

    private Posting createPosting(Transaction transaction, Account account, BigDecimal amount, PostingDirection postingDirection) {
        return Posting.builder().transaction(transaction).account(account).amount(amount).direction(postingDirection).build();
    }

    private Account getAccount(UUID userId, CurrencyType currency) {

        return accountRepository.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new AccountNotFoundException(userId));

    }

    private Account getSystemAccount(String name, CurrencyType currency) {
        return accountRepository.findByNameAndCurrency(name, currency)
                .orElseThrow(() -> new MissingSystemAccountException(name));
    }

    private void validateRequest(TransactionRequest request) {
        if (request.getType() == null) {
            throw new UnsupportedTransactionException("Transaction Type is missing");
        }

        if (request.getType() != TransactionType.DEPOSIT && request.getSenderId() == null) {
            throw new IllegalArgumentException("Sender ID is required for " + request.getType());
        }


        if (request.getType() != TransactionType.WITHDRAWAL && request.getType() != TransactionType.FEE && request.getReceiverId() == null) {
            throw new IllegalArgumentException("Receiver ID is required for " + request.getType());
        }

    }

    private void saveOutboxEvent(Transaction transaction, String eventType) {

        try {
            String jsonPayload = objectMapper.writeValueAsString(transaction);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(transaction.getReferenceId())
                    .eventType(eventType)
                    .payload(jsonPayload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outboxEvent);
        } catch (JacksonException e) {
            log.error("Failed to serialize transaction for outbox", e);
            throw new RuntimeException("Serialization failure", e);
        }

    }

}
