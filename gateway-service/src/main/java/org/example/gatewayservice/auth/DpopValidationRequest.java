package org.example.gatewayservice.auth;

public record DpopValidationRequest(
        String proof,
        String expectedJkt,
        String httpMethod,
        String httpUri,
        String opaqueToken
) {
}
