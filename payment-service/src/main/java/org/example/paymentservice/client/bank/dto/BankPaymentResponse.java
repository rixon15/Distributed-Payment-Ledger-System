package org.example.paymentservice.client.bank.dto;

import java.util.UUID;

public record BankPaymentResponse(
        UUID transactionId,
        BankPaymentStatus status,
        String reasonCode
) {
}
