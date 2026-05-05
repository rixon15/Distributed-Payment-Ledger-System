package org.example.paymentservice.simulator.riskengine.controller;

import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/mock-risk-engine")
@Profile({"dev", "test"})
public class MockRiskController {

    @PostMapping("/evaluate")
    public RiskResponse evaluateRisk(@RequestBody RiskRequest request) {

        if (request.amount().compareTo(BigDecimal.valueOf(10_000.00)) > 0) {
            return new RiskResponse(RiskStatus.REJECTED, "Amount is too high");
        }

        if (request.userId().toString().endsWith("666")) {
            return new RiskResponse(RiskStatus.REJECTED, "User flagged for suspicious activity");
        }

        return new RiskResponse(RiskStatus.APPROVED, "Verified safe transaction");
    }

}
