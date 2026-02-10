package com.openfashion.ledgerservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openfashion.ledgerservice.model.CurrencyType;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationRequest(
        UUID userId,

        @JsonProperty("amount")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "#,##0.0000")
        BigDecimal amount,

        CurrencyType currency,
        UUID referenceId
) {
}
