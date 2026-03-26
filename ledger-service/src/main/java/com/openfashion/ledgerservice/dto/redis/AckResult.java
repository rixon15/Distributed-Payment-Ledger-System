package com.openfashion.ledgerservice.dto.redis;

import java.util.List;

public record AckResult(
        int requested,
        int acked,
        List<String> missingIds,
        boolean success,
        String error
) {
}
