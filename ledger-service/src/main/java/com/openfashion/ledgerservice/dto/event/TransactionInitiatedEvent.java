package com.openfashion.ledgerservice.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TransactionInitiatedEvent (
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("aggregatedId") String referenceId, // Map aggregatedId to your referenceId
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("payload") TransactionPayload payload // Nested object
) {}
