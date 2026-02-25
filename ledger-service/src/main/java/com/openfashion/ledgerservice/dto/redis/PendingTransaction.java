package com.openfashion.ledgerservice.dto.redis;

import com.openfashion.ledgerservice.model.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public record PendingTransaction(
        UUID referenceId,
        UUID debitAccountId,
        UUID creditAccountId,
        BigDecimal amount,
        TransactionType type,
        Long timestamp
) {
}
