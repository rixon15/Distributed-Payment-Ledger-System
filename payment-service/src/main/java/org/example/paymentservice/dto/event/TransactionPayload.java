package org.example.paymentservice.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionPayload(
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        String userMessage,
        Instant timestamp,
        Map<String, String> metadata
) {}
