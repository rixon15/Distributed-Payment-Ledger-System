package com.openfashion.ledgerservice.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionInitiatedEvent (
        @JsonProperty("eventId") String eventId,
        @JsonProperty("referenceId") String referenceId, // <--- Ensures "referenceId" maps here
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("type") String type,
        @JsonProperty("senderId") UUID senderId,
        @JsonProperty("receiverId") UUID receiverId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("metadata") String metadata
) {}
