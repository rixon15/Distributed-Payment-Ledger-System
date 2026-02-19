package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransferResolutionStrategy implements AccountResolutionStrategy{
    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.TRANSFER ||
                type == TransactionType.PAYMENT ||
                type == TransactionType.ADJUSTMENT ||
                type == TransactionType.REFUND;
    }

    @Override
    public AccountPair resolve(TransactionRequest request, AccountRepository repository) {
        Account debit = repository.findByUserIdAndCurrency(request.getSenderId(), request.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException(request.getSenderId()));

        Account credit = repository.findByUserIdAndCurrency(request.getReceiverId(), request.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException(request.getReceiverId()));

        return new AccountPair(debit, credit);
    }
}
