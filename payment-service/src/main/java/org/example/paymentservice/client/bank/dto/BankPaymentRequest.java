package org.example.paymentservice.client.bank.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BankPaymentRequest(
        UUID referenceId,
        UUID accountId,
        BigDecimal amount,
        String currency
) {}
