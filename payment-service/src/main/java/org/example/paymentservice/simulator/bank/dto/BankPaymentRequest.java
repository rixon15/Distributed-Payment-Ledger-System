package org.example.paymentservice.simulator.bank.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BankPaymentRequest(
        UUID referenceId,
        String accountId,
        BigDecimal amount,
        String currency
) {}
