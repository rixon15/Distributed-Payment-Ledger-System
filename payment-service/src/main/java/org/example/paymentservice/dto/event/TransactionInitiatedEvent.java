package org.example.paymentservice.dto.event;

import java.time.Instant;
import java.util.UUID;

public record TransactionInitiatedEvent(
        UUID eventId,
        String eventType,
        String aggregatedId, // Payment ID the ledger uses as referenceId
        Instant timestamp,
        TransactionPayload payload
) {}

