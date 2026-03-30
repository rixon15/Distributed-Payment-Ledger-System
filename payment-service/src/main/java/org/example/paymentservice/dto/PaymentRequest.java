package org.example.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.example.paymentservice.core.validation.ValidPaymentRequest;
import org.example.paymentservice.model.PaymentType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * API request contract for starting payment execution.
 *
 * <p>Validated at controller boundary and further constrained by
 * {@code @ValidPaymentRequest} for cross-field rules.
 *
 * @param receiverId destination user for transfer-like operations
 * @param idempotencyKey client-provided request deduplication key
 * @param type payment business type
 * @param amount requested amount
 * @param currency ISO currency code string
 */
@ValidPaymentRequest
public record PaymentRequest(
        UUID receiverId,
        @NotBlank String idempotencyKey,
        @NotNull PaymentType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency
) {
}
