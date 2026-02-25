package com.openfashion.ledgerservice.dto.event;

import com.openfashion.ledgerservice.model.CurrencyType;

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
