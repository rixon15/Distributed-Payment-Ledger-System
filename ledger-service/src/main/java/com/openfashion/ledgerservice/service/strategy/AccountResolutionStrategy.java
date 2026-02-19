package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;

public interface AccountResolutionStrategy {
    boolean supports(TransactionType type);
    AccountPair resolve(TransactionRequest request, AccountRepository repository);
}
