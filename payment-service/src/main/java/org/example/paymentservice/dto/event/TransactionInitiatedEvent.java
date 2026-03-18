package org.example.paymentservice.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.paymentservice.model.PaymentType;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionInitiatedEvent(
        UUID eventId,
        PaymentType eventType,
        UUID aggregatedId, // Payment ID the ledger uses as referenceId
        Instant timestamp,
        TransactionPayload payload
) {
}

