package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeeResolutionStrategy implements AccountResolutionStrategy {
    private static final String REVENUE_ACCOUNT = "REVENUE_ACCOUNT";

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.FEE;
    }

    @Override
    public AccountPair resolve(TransactionRequest request, AccountRepository repository) {
        Account debit = repository.findByUserIdAndCurrency(request.getSenderId(), request.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException(request.getSenderId()));

        Account credit = repository.findByNameAndCurrency(REVENUE_ACCOUNT, request.getCurrency())
                .orElseThrow(() -> new MissingSystemAccountException(REVENUE_ACCOUNT));

        return new AccountPair(debit, credit);
    }

}
