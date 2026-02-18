package org.example.paymentservice.dto.event;

import org.example.paymentservice.model.TransactionType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record TransactionPayload(
        TransactionType type,
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        String currency,
        String userMessage,
        Map<String, String> metadata
) {}
