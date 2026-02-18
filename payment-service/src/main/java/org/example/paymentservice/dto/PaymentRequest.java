package org.example.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.example.paymentservice.core.validation.ValidPaymentRequest;
import org.example.paymentservice.model.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

@ValidPaymentRequest
public record PaymentRequest(
        UUID receiverId,
        @NotBlank String idempotencyKey,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency
) {
}
