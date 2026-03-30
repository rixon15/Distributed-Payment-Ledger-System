package com.openfashion.ledgerservice.dto.consumer;

/**
 * Correlation token for Redis-backed batch completion tracking.
 *
 * @param batchId unique batch identifier used in Redis metadata and stream entries
 * @param expectedCount number of accepted records expected to complete
 */
public record BatchToken(
        String batchId,
        int expectedCount
) {
}
