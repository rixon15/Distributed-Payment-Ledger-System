package com.openfashion.ledgerservice.dto.event;

import java.math.BigDecimal;

public record WithdrawalPayload(
        BigDecimal amount,
        String currency // Using String here to be safe, then convert to Enum
) {
    public WithdrawalPayload {
        if (amount != null) {
            amount = amount.setScale(4, java.math.RoundingMode.HALF_EVEN);
        }
    }
}
