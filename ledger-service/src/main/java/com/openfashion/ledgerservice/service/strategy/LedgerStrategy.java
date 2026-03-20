package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class LedgerStrategy {

    protected final AccountRepository accountRepository;

    public abstract boolean supports(TransactionType transactionType);

    public abstract TransactionRequest mapToRequest(TransactionInitiatedEvent event);

}
