package org.example.paymentservice.dto.event;


import org.example.paymentservice.model.CurrencyType;

import java.math.BigDecimal;

public record WithdrawalPayload(
        BigDecimal amount,
        CurrencyType currency
) {
    public WithdrawalPayload {
        if (amount != null) {
            amount = amount.setScale(4, java.math.RoundingMode.HALF_EVEN);
        }
    }
}
