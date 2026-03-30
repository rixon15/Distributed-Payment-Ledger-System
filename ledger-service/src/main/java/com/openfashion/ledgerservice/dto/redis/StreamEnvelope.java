package com.openfashion.ledgerservice.dto.redis;

/**
 * Wrapper around a Redis stream record plus its deserialized payload.
 *
 * <p>The envelope carries both the raw JSON and parsed form so the pipeline can
 * persist business data while still preserving enough context for logging or DLQ routing.
 *
 * @param streamId Redis stream record id
 * @param batchId batch correlation id propagated from Redis staging
 * @param rawJson original serialized payload
 * @param data parsed payload object
 * @param deliveryCount number of delivery attempts observed for this record
 */
public record StreamEnvelope<T>(
        String streamId, // Redis record id (e.g., 1712345678901-0)
        String batchId,
        String rawJson,
        T data,
        long deliveryCount
) {
    /**
     * Normalizes delivery count so downstream retry logic never sees zero or negative attempts.
     */
    public StreamEnvelope {
        if (deliveryCount <= 0) {
            deliveryCount = 1;
        }
    }
}
