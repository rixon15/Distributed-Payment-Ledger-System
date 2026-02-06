package org.example.paymentservice.simulator.riskengine.dto;

public record RiskResponse(
        RiskStatus status,
        String reason
) {}

