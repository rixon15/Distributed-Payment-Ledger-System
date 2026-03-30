package com.openfashion.ledgerservice.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openfashion.ledgerservice.model.TransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound event consumed from Kafka topic {@code transaction.request}.
 *
 * <p>This event is produced indirectly from the payment-service outbox.
 * The JSON property {@code aggregatedId} is mapped into {@code referenceId}
 * and becomes the business reference used throughout the ledger pipeline.
 *
 * @param eventId unique event id
 * @param eventType business transaction type to map
 * @param referenceId upstream business reference id
 * @param timestamp event creation time
 * @param payload transaction details used by strategy mapping
 */
public record TransactionInitiatedEvent (
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") TransactionType eventType,
        @JsonProperty("aggregatedId") UUID referenceId, // Map aggregatedId to your referenceId
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("payload") TransactionPayload payload // Nested object
) {}
