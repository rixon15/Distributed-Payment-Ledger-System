package org.example.paymentservice.simulator.riskengine.service;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class ExternalRiskClient {

    private final RestClient restClient;

    public ExternalRiskClient(
            RestClient.Builder builder,
            @Value("${app.risk-engine.url}") String riskUrl
    ) {
        this.restClient = builder.baseUrl(riskUrl).build();
    }

    public RiskResponse evaluate(RiskRequest request) {
        try {
            return restClient.post()
                    .uri("/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskResponse.class);
        } catch (Exception e) {
            log.error("Unexcepted error occurred: {}", e.getMessage());
            // For now, let's Fail Closed for safety:
            return new RiskResponse(RiskStatus.REJECTED, "Risk Service Unavailable");
        }
    }

}
