package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.core.exceptions.*;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.ReleaseRequest;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalCompleteEvent;
import com.openfashion.ledgerservice.model.OutboxEvent;
import com.openfashion.ledgerservice.model.OutboxStatus;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.serializer.support.SerializationFailedException;
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
    private static final String FAILED_TRANSACTION = "transaction.failed";
    private static final String WITHDRAWAL_ACCOUNT_NAME = "PENDING_WITHDRAWAL";
    private static final String WORLD_ACCOUNT_NAME = "WORLD_LIQUIDITY";

    @Transactional(isolation = Isolation.SERIALIZABLE)
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2))
    public void processTransaction(TransactionRequest request) {

        validateTransactionRequest(request);

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
                debitAccount = getSystemAccount(WORLD_ACCOUNT_NAME, request.getCurrency());
                creditAccount = getAccount(request.getReceiverId(), request.getCurrency());
            }
            case WITHDRAWAL -> {
                debitAccount = getAccount(request.getSenderId(), request.getCurrency());
                creditAccount = getSystemAccount(WORLD_ACCOUNT_NAME, request.getCurrency());
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

            saveOutboxEvent(transaction, FAILED_TRANSACTION);
            return;
        }

        // We ONLY enforce NSF checks on User ASSET accounts.
        // - System Accounts (EQUITY, INCOME, EXPENSE) are unbounded by design (e.g. World Liquidity must go negative to mint money).
        // - LIABILITY accounts (Credit Cards) would require a different check (Credit Limit), not a Zero-Floor check.
        if (debitAccount.getType() == AccountType.ASSET && debitAccount.getBalance().compareTo(request.getAmount()) < 0) {
            transaction.setStatus(TransactionStatus.REJECTED_NSF);
            transactionRepository.save(transaction);
            log.info("Transaction rejected (NSF): {}", request.getReferenceId());

            saveOutboxEvent(transaction, FAILED_TRANSACTION);
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

        saveOutboxEvent(transaction, "transaction.posted");

        log.info("Transaction {} processed successfully in {} ms", request.getReferenceId(), System.currentTimeMillis() - start);

    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2))
    @Transactional
    @Override
    public void reserveFunds(ReservationRequest request) {

        log.info("Starting fund reservation for user: {}", request.userId());

        validateReservationRequest(request);

        if (transactionRepository.existsByReferenceId(request.referenceId().toString())) {
            log.warn("Reservation already exists for reference: {}", request.referenceId());
            return;
        }

        Account userAccount = accountRepository.findByUserIdAndCurrency(request.userId(), request.currency())
                .orElseThrow(() -> new AccountNotFoundException(request.userId()));
        Account pendingAccount = accountRepository.findByNameAndCurrency(WITHDRAWAL_ACCOUNT_NAME, request.currency())
                .orElseThrow(() -> new MissingSystemAccountException(WITHDRAWAL_ACCOUNT_NAME));

        if (userAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(userAccount.getId());
        }
        if (userAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(userAccount.getId());
        }

        BigDecimal funds = MoneyUtil.format(request.amount());

        Transaction transaction = Transaction.builder()
                .referenceId(request.referenceId().toString())
                .type(TransactionType.WITHDRAWAL)
                .status(TransactionStatus.PENDING)
                .effectiveDate(Instant.now())
                .build();

        List<Posting> postings = List.of(
                createPosting(transaction, userAccount, funds, PostingDirection.DEBIT),
                createPosting(transaction, pendingAccount, funds, PostingDirection.CREDIT)
        );

        userAccount.setBalance(userAccount.getBalance().subtract(funds));
        pendingAccount.setBalance(pendingAccount.getBalance().add(funds));

        transactionRepository.save(transaction);
        accountRepository.save(userAccount);
        accountRepository.save(pendingAccount);
        postingRepository.saveAll(postings);

        log.info("Reservation of {} {} was successful for user: {}", funds, request.currency(), request.userId());
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2))
    @Transactional
    @Override
    public void releaseFunds(ReleaseRequest request) {
        log.info("Attempting to release reserved funds for payment: {}", request.referenceId());

        Transaction transaction = transactionRepository.findByReferenceId(request.referenceId().toString())
                .orElseThrow(() -> new TransactionNotFoundException(request.referenceId()));

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            log.warn("Cannot release funds: Transaction {} is already in status {}",
                    request.referenceId(), transaction.getStatus());
            return;
        }

        Posting originalDebit = postingRepository.findByTransactionAndDirection(transaction, PostingDirection.DEBIT)
                .orElseThrow();
        Account userAccount = originalDebit.getAccount();

        Account pendingAccount = accountRepository.findByNameAndCurrency(WITHDRAWAL_ACCOUNT_NAME, userAccount.getCurrency())
                .orElseThrow(() -> new MissingSystemAccountException(WITHDRAWAL_ACCOUNT_NAME));

        BigDecimal amount = originalDebit.getAmount();

        List<Posting> releasePostings = List.of(
                createPosting(transaction, pendingAccount, amount, PostingDirection.DEBIT),
                createPosting(transaction, userAccount, amount, PostingDirection.CREDIT)
        );

        pendingAccount.setBalance(pendingAccount.getBalance().subtract(amount));
        userAccount.setBalance(userAccount.getBalance().add(amount));

        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setMetadata("Funds released due to payment failure");

        transactionRepository.save(transaction);
        accountRepository.save(pendingAccount);
        accountRepository.save(userAccount);
        postingRepository.saveAll(releasePostings);

        log.info("Successfully released {} back to user {}", amount, userAccount.getUserId());
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2))
    @Transactional
    @Override
    public void processWithdrawal(WithdrawalCompleteEvent event) {

        log.info("Processing withdrawal: {}", event.referenceId());

        validateWithdrawalCompletedEvent(event);

        Transaction pendingTransaction = transactionRepository.findByReferenceId(event.referenceId().toString())
                .orElseThrow(() -> new TransactionNotFoundException(event.referenceId()));

        if (pendingTransaction.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction status is not PENDING");
        }

        Posting posting = postingRepository.findByTransactionAndDirection(pendingTransaction, PostingDirection.CREDIT)
                .orElseThrow(() -> new PostingNotFoundException(
                        pendingTransaction.getId(),
                        PostingDirection.CREDIT
                ));

        if (event.amount().compareTo(posting.getAmount()) != 0) {
            throw new DataMismatchException("Event data differs from transaction/posting data");
        }


        Account withdrawalAccount = posting.getAccount();
        Account worldAccount = accountRepository.findByNameAndCurrency(WORLD_ACCOUNT_NAME, event.currency())
                .orElseThrow(() -> new MissingSystemAccountException(WORLD_ACCOUNT_NAME));

        List<Posting> postings = List.of(
                createPosting(pendingTransaction, withdrawalAccount, event.amount(), PostingDirection.DEBIT),
                createPosting(pendingTransaction, worldAccount, event.amount(), PostingDirection.CREDIT)
        );

        withdrawalAccount.setBalance(withdrawalAccount.getBalance().subtract(event.amount()));
        worldAccount.setBalance(worldAccount.getBalance().add(event.amount()));

        pendingTransaction.setStatus(TransactionStatus.POSTED);

        transactionRepository.save(pendingTransaction);
        accountRepository.save(withdrawalAccount);
        accountRepository.save(worldAccount);
        postingRepository.saveAll(postings);

        saveOutboxEvent(pendingTransaction, "WITHDRAWAL_SETTLED");
        log.info("Withdrawal was successful. Transaction ID: {}", pendingTransaction.getId());
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

    private void validateTransactionRequest(TransactionRequest request) {
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

    private void validateReservationRequest(ReservationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ReservationRequest must not be null");
        }
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (request.currency() == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
        if (request.referenceId() == null) {
            throw new IllegalArgumentException("referenceId must not be null");
        }
    }

    private void validateWithdrawalCompletedEvent(WithdrawalCompleteEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("WithdrawalCompleteEvent must not be null");
        }
        if (event.referenceId() == null) {
            throw new IllegalArgumentException("WithdrawalCompleteEvent.referenceId must not be null");
        }
        if (event.amount() == null) {
            throw new IllegalArgumentException("WithdrawalCompleteEvent.amount must not be null");
        }
        if (event.currency() == null) {
            throw new IllegalArgumentException("WithdrawalCompleteEvent.currency must not be null");
        }
        if (event.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("WithdrawalCompleteEvent.amount must be positive");
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
            throw new SerializationFailedException("Serialization failure", e);
        }

    }

}
