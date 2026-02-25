package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.InsufficientFundsException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.core.exceptions.UnsupportedTransactionException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalEvent;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.LedgerService;
import com.openfashion.ledgerservice.service.RedisService;
import com.openfashion.ledgerservice.service.strategy.AccountPair;
import com.openfashion.ledgerservice.service.strategy.AccountResolutionStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerServiceImp implements LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxRepository outboxRepository;
    private final RedisService redisService;
    private static final String FAILED_TRANSACTION = "TRANSACTION_FAILED";
    private static final String WITHDRAWAL_ACCOUNT_NAME = "PENDING_WITHDRAWAL";
    private static final String WORLD_ACCOUNT_NAME = "WORLD_LIQUIDITY";

    private final Map<TransactionType, AccountResolutionStrategy> strategyMap = new EnumMap<>(TransactionType.class);
    private final List<AccountResolutionStrategy> strategies;

    @PostConstruct
    public void initStrategy() {
        for (TransactionType type : TransactionType.values()) {
            strategies.stream()
                    .filter(s -> s.supports(type))
                    .findFirst()
                    .ifPresentOrElse(
                            s -> strategyMap.put(type, s),
                            () -> log.warn("No strategy found for TransactionType: {}", type)
                    );
        }
    }

    @Override
    public void processTransaction(TransactionRequest request) {

        validateTransactionRequest(request);

        if (transactionRepository.existsByReferenceId(request.getReferenceId())) {
            log.warn("Idempotency Triggered: Transaction {} already processed.", request.getReferenceId());
            return;
        }

        AccountResolutionStrategy strategy = strategyMap.get(request.getType());
        if (strategy == null) {
            throw new UnsupportedTransactionException(String.valueOf(request.getType()));
        }

        AccountPair accounts = strategy.resolve(request, accountRepository);

        BigDecimal pendingNet = redisService.getPendingNetChanges(accounts.debit().getId());
        BigDecimal softBalance = accounts.debit().getBalance().add(pendingNet);

        if (accounts.debit().getType() == AccountType.ASSET && softBalance.compareTo(request.getAmount()) < 0) {
            saveRejectedTransaction(request, TransactionStatus.REJECTED_NSF);
            return;
        }

        PendingTransaction pendingTransaction = new PendingTransaction(
                UUID.fromString(request.getReferenceId()),
                accounts.debit().getId(),
                accounts.credit().getId(),
                request.getAmount(),
                request.getType(),
                System.currentTimeMillis()

        );

        redisService.bufferTransactions(pendingTransaction);

        log.info("Transaction {} accepted and buffered", request.getReferenceId());

    }

    @Override
    public void handleWithdrawalEvent(WithdrawalEvent event) {
        log.info("Processing {} for reference: {}", event.status(), event.referenceId());

        switch (event.status()) {
            case RESERVED -> bufferReservation(event);
            case CONFIRMED -> bufferSettlement(event);
            case FAILED -> bufferRelease(event);
        }
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

    private void saveRejectedTransaction(TransactionRequest request, TransactionStatus status) {
        log.warn("Transaction {} rejected: {}", request.getReferenceId(), status);

        Transaction transaction = Transaction.builder()
                .referenceId(request.getReferenceId())
                .type(request.getType())
                .status(status)
                .effectiveDate(Instant.now())
                .metadata(request.getMetadata())
                .build();

        transactionRepository.save(transaction);

        saveOutboxEvent(transaction, FAILED_TRANSACTION);
    }

    private void bufferReservation(WithdrawalEvent event) {
        Account userAccount = accountRepository.findByUserIdAndCurrency(event.userId(), event.payload().currency())
                .orElseThrow(() -> new AccountNotFoundException(event.userId()));
        Account pendingAccount = getSystemAccount(WITHDRAWAL_ACCOUNT_NAME, event.payload().currency());

        BigDecimal pendingNet = redisService.getPendingNetChanges(userAccount.getId());
        BigDecimal softBalance = userAccount.getBalance().add(pendingNet);

        if (softBalance.compareTo(event.payload().amount()) < 0) {
            // Note: If this fails here, it means the Payment Service sent a reservation
            // for money the user doesn't have. This shouldn't happen if Payment API checks balance too.
            throw new InsufficientFundsException(userAccount.getId());
        }

        pushToBuffer(event, userAccount.getUserId(), pendingAccount.getId(), TransactionType.WITHDRAWAL_RESERVE);
    }

    private void bufferSettlement(WithdrawalEvent event) {
        Account pendingAccount = getSystemAccount(WITHDRAWAL_ACCOUNT_NAME, event.payload().currency());
        Account worldAccount = getSystemAccount(WORLD_ACCOUNT_NAME, event.payload().currency());

        pushToBuffer(event, pendingAccount.getId(), worldAccount.getId(), TransactionType.WITHDRAWAL_SETTLE);
    }

    private void bufferRelease(WithdrawalEvent event) {
        Account userAccount = accountRepository.findByUserIdAndCurrency(event.userId(), event.payload().currency())
                .orElseThrow(() -> new AccountNotFoundException(event.userId()));
        Account pendingAccount = getSystemAccount(WITHDRAWAL_ACCOUNT_NAME, event.payload().currency());

        pushToBuffer(event, pendingAccount.getId(), userAccount.getId(), TransactionType.WITHDRAWAL_RELEASE);
    }

    private void pushToBuffer(WithdrawalEvent event, UUID debitId, UUID creditId, TransactionType transactionType) {
        PendingTransaction pendingTransaction = new PendingTransaction(
                event.referenceId(),
                debitId,
                creditId,
                event.payload().amount(),
                transactionType,
                event.timestamp()
        );

        redisService.bufferTransactions(pendingTransaction);
    }

    private Account getSystemAccount(String name, CurrencyType currency) {
        return accountRepository.findByNameAndCurrency(name, currency)
                .orElseThrow(() -> new MissingSystemAccountException(name));
    }
}
