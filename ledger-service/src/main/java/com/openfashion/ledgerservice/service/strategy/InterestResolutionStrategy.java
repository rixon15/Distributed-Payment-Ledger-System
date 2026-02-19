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
public class InterestResolutionStrategy implements AccountResolutionStrategy {
    private static final String INTEREST_ACCOUNT = "INTEREST_EXPENSE";

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.INTEREST;
    }

    @Override
    public AccountPair resolve(TransactionRequest request, AccountRepository repository) {
        Account debit = repository.findByNameAndCurrency(INTEREST_ACCOUNT, request.getCurrency())
                .orElseThrow(() -> new MissingSystemAccountException(INTEREST_ACCOUNT));

        Account credit = repository.findByUserIdAndCurrency(request.getReceiverId(), request.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException(request.getReceiverId()));

        return  new AccountPair(debit, credit);
    }
}
