package com.openfashion.ledgerservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.openfashion.ledgerservice.model.CurrencyType;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationRequest(
        UUID userId,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,

        CurrencyType currency,
        UUID referenceId
) {
}
