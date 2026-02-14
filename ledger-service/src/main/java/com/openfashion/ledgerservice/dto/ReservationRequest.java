package com.openfashion.ledgerservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openfashion.ledgerservice.model.CurrencyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public record ReservationRequest(
        UUID userId,

        @JsonProperty("amount")
        BigDecimal amount,

        CurrencyType currency,
        UUID referenceId
) {

    public ReservationRequest {
        if (amount != null) {
            amount = amount.setScale(4, RoundingMode.HALF_UP);
        }
    }
}
