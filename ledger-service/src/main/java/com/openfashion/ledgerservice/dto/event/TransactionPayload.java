package com.openfashion.ledgerservice.dto.event;

import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Business payload embedded inside an inbound transaction event.
 *
 * <p>This payload carries the user identities, amount, currency, and upstream status
 * that strategy implementations use to derive concrete debit/credit legs.
 *
 * @param senderId initiating user
 * @param receiverId receiving user when applicable
 * @param amount business amount
 * @param currency transaction currency
 * @param status upstream lifecycle status
 * @param userMessage optional user-facing message
 * @param timestamp event-side timestamp
 * @param metadata optional extra attributes
 */
public record TransactionPayload(
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        CurrencyType currency,
        TransactionStatus status,
        String userMessage,
        Instant timestamp,
        Map<String, String> metadata
) {}
