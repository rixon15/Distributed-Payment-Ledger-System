package com.openfashion.ledgerservice.dto.event;

import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionResultEvent(
        UUID referenceId,
        TransactionType type,
        TransactionStatus status,
        String reasonCode,
        String message,
        Instant timestamp
) {
}
