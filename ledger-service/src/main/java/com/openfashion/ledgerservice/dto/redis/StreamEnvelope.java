package com.openfashion.ledgerservice.dto.redis;

public record StreamEnvelope<T>(
        String streamId, // Redis record idm e,g, 171,,,-0
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
