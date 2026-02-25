package com.openfashion.ledgerservice.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record WithdrawalConfirmedEvent(
        @JsonProperty("aggregatedId") UUID referenceId, // Map "aggregatedId" from JSON to this field
        @JsonProperty("payload") WithdrawalPayload payload
) {}
