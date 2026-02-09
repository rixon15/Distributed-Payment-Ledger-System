package org.example.paymentservice.dto;

import org.example.paymentservice.model.CurrencyType;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationRequest(
        UUID userId,
        BigDecimal amount,
        CurrencyType currencyType,
        UUID referenceId
) {}
