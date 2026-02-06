package org.example.paymentservice.dto;

public record ErrorResponse(
        String error,
        int status
) {
}
