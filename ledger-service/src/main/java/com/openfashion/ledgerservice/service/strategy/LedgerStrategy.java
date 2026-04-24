package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Base strategy for mapping inbound transaction events into debit/credit ledger instructions.
 *
 * <p>Each implementation owns mapping rules for a specific {@code TransactionType}
 * (or set of related types) and resolves concrete account ids before persistence.
 */
@RequiredArgsConstructor
@Slf4j
public abstract class LedgerStrategy {

    protected final AccountRepository accountRepository;
    protected static final String PENDING_WITHDRAWAL_ACC = "PENDING_WITHDRAWAL";
    protected static final String WORLD_LIQUIDITY_ACC = "WORLD_LIQUIDITY";

    /**
     * Returns whether this strategy handles the given transaction type.
     */
    public abstract boolean supports(TransactionType transactionType);

    /**
     * Checks if the transaction request is valid based on the business rules
     */
    public abstract boolean isValidTransaction(TransactionInitiatedEvent transactionInitiatedEvent);

    /**
     * Maps an inbound event into a normalized request with resolved debit/credit account ids.
     *
     * @throws com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException
     * when a user account cannot be resolved
     * @throws com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException
     * when a required system account is missing
     */
    public abstract TransactionRequest mapToRequest(TransactionInitiatedEvent event);

    /**
     * Resolves user account id for a currency.
     */
    protected UUID resolveUserAccount(UUID userId, CurrencyType currencyType) {

        return accountRepository.findByUserIdAndCurrency(userId, currencyType)
                .orElseThrow(() -> new AccountNotFoundException(userId))
                .getId();

    }

    /**
     * Resolves system account id by logical name and currency.
     */
    protected UUID resolveSystemAccount(String systemAccountName, CurrencyType currencyType) {
        return accountRepository.findByNameAndCurrency(systemAccountName, currencyType)
                .orElseThrow(() -> new MissingSystemAccountException(systemAccountName))
                .getId();
    }

    public TransactionRequest createRejectedRequest(TransactionInitiatedEvent event) {
        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(event.referenceId());
        request.setType(event.eventType());
        request.setSenderId(event.payload().senderId());
        request.setReceiverId(event.payload().receiverId());
        request.setAmount(MoneyUtil.format(event.payload().amount()));
        request.setCurrency(event.payload().currency());

        log.info("Mapped Rejected Request for reference: {}", event.referenceId());

        return request;
    }

}
