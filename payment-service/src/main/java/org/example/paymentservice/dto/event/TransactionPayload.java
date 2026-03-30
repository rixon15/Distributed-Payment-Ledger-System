package org.example.paymentservice.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Business payload embedded in payment outbox transaction events.
 *
 * @param senderId initiating user id
 * @param receiverId receiving user id when applicable
 * @param amount payment amount
 * @param currency currency code string
 * @param status current payment-to-ledger transition state
 * @param userMessage optional user-facing detail
 * @param timestamp payload timestamp
 * @param metadata optional key-value metadata
 */
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
