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
public class WithdrawalResolutionStrategy implements AccountResolutionStrategy {
    private static final String WORLD_ACCOUNT = "WORLD_LIQUIDITY";

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.WITHDRAWAL;
    }

    @Override
    public AccountPair resolve(TransactionRequest request, AccountRepository repository) {
        Account debit = repository.findByUserIdAndCurrency(request.getSenderId(), request.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException(request.getSenderId()));

        Account credit = repository.findByNameAndCurrency(WORLD_ACCOUNT, request.getCurrency())
                .orElseThrow(() -> new MissingSystemAccountException(WORLD_ACCOUNT));

        return new AccountPair(debit, credit);
    }
}
