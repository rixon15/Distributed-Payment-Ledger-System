package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.model.Account;

public record AccountPair(
        Account debit,
        Account credit
) {}
