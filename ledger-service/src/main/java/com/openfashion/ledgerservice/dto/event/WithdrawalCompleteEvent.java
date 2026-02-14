package com.openfashion.ledgerservice.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openfashion.ledgerservice.model.CurrencyType;

import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawalCompleteEvent(
        @JsonProperty("referenceId") UUID referenceId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") CurrencyType currency
) {
    public WithdrawalCompleteEvent {
        // Enforce 4 decimal places and bankers rounding immediately
        if (amount != null) {
            amount = amount.setScale(4, java.math.RoundingMode.HALF_EVEN);
        }
    }
}
