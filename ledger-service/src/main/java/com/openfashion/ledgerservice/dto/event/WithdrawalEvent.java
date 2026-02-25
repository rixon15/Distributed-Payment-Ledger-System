package com.openfashion.ledgerservice.dto.event;

import java.util.UUID;

public record WithdrawalEvent (
        UUID referenceId,
        UUID userId,
        WithdrawalStatus status,
        WithdrawalPayload payload,
        long timestamp
) {}

