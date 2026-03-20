package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public abstract class LedgerStrategy {

    protected final AccountRepository accountRepository;
    protected static final String PENDING_WITHDRAWAL_ACC = "PENDING_WITHDRAWAL";
    protected static final String WORLD_LIQUIDITY_ACC = "WORLD_LIQUIDITY";

    public abstract boolean supports(TransactionType transactionType);

    public abstract TransactionRequest mapToRequest(TransactionInitiatedEvent event);

    protected UUID resolveUserAccount(UUID userId, CurrencyType currencyType) {

        return accountRepository.findByUserIdAndCurrency(userId, currencyType)
                .orElseThrow(() -> new AccountNotFoundException(userId))
                .getId();

    }

    protected UUID resolveSystemAccount(String systemAccountName, CurrencyType currencyType) {
        return accountRepository.findByNameAndCurrency(systemAccountName, currencyType)
                .orElseThrow(() -> new MissingSystemAccountException(systemAccountName))
                .getId();
    }
}
