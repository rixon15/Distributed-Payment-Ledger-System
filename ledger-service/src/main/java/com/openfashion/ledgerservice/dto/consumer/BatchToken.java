package com.openfashion.ledgerservice.dto.consumer;

public record BatchToken(
        String batchId,
        int expectedCount
) {
}
