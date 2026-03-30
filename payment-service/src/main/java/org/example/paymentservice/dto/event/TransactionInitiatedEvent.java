package org.example.paymentservice.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.paymentservice.model.PaymentType;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event payload sent from payment-service toward ledger-service.
 *
 * <p>{@code aggregatedId} carries payment id and is consumed by ledger as reference id.
 *
 * @param eventId unique event identifier
 * @param eventType payment type mapped to ledger transaction handling
 * @param aggregatedId originating payment id
 * @param timestamp event creation time
 * @param payload transaction details and status context
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionInitiatedEvent(
        UUID eventId,
        PaymentType eventType,
        UUID aggregatedId, // Payment ID the ledger uses as referenceId
        Instant timestamp,
        TransactionPayload payload
) {
}

