package org.example.paymentservice.simulator.bank.dto;

import java.util.UUID;

public record BankPaymentResponse(
        UUID transactionId,
        BankPaymentStatus status,
        String reasonCode
) {
}
