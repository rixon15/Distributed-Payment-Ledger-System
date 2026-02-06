package org.example.paymentservice.dto;

import java.util.UUID;

public record SagaActionResult(
        boolean success,
        UUID externalId,
        String errorMessage,
        String failureCode
) {

    public static SagaActionResult success(UUID externalId) {
        return new SagaActionResult(true, externalId, null, null);
    }

    public static SagaActionResult failure(String message, String code) {
        return new SagaActionResult(false, null, message, code);
    }

}
