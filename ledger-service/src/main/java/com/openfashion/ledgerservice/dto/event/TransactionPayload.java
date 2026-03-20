package com.openfashion.ledgerservice.dto.event;

import com.openfashion.ledgerservice.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
