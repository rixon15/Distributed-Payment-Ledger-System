package com.openfashion.ledgerservice.dto.event;

import java.util.UUID;

public record WithdrawalEvent (
        UUID referenceId,
        UUID usersId,
        WithdrawalStatus status,
        WithdrawalPayload payload,
        long timestamp
) {}

