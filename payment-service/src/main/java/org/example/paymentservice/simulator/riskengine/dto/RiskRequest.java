package org.example.paymentservice.simulator.riskengine.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RiskRequest(
        UUID userId,
        BigDecimal amount,
        String ipAddress
) {
}
