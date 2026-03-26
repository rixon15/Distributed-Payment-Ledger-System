package com.openfashion.ledgerservice.dto.redis;

public record StreamEnvelope<T>(
        String streamId, // Redis record id (e.g., 1712345678901-0)
        String batchId,
        String rawJson,
        T data,
        long deliveryCount
) {
    public StreamEnvelope {
        if (deliveryCount <= 0) {
            deliveryCount = 1;
        }
    }
}
