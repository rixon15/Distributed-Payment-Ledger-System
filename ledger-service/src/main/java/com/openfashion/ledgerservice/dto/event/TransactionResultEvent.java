package com.openfashion.ledgerservice.dto.event;

import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound result event written to the ledger outbox for Debezium publication.
 *
 * <p>This is the response contract sent back toward the payment side through
 * Kafka topic {@code transaction.response}.
 *
 * @param referenceId originating business reference id
 * @param type concrete ledger transaction type that was processed
 * @param status final ledger result status
 * @param reasonCode short machine-readable outcome code
 * @param message human-readable outcome message
 * @param timestamp event creation time
 */
public record TransactionResultEvent(
        UUID referenceId,
        TransactionType type,
        TransactionStatus status,
        String reasonCode,
        String message,
        Instant timestamp
) {
}
