package org.example.gatewayservice.auth;

public record TokenResolutionRequest(
        String opaqueToken,
        String dpopProof,
        String httpMethod,
        String httpUri
) {
}
