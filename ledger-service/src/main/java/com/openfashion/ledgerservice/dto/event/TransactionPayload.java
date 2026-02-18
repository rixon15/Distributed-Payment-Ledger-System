package com.openfashion.ledgerservice.dto.event;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record TransactionPayload(
        String type,
        UUID senderId,
        UUID receiverId,
        BigDecimal amount,
        String currency,
        String userMessage,
        Map<String, String> metadata
) {}
