package org.example.paymentservice;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.EnableWireMock;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "scheduling.enabled=false",
        "app.risk-engine.url=${wiremock.server.baseUrl}/mock-risk-engine"
})
@Testcontainers
@AutoConfigureMockMvc
@EnableWireMock
class PaymentIdempotencyIntegrationTest {
    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    //Note: WireMock should support HTTP/2 and normally fall back to HTTP/1.1 but for some reason it's bugged

    @Test
    void shouldPreventDuplicateRequestsAcrossNetworkCalls() throws Exception {
        UUID receiverId = UUID.randomUUID();
        String jsonRequest = """
                    {
                        "idempotencyKey": "unique-123",
                        "amount": 100.00,
                        "type": "TRANSFER",
                        "currency": "USD",
                        "receiverId": "%s"
                    }
                """.formatted(receiverId);

        stubFor(WireMock.post(urlEqualTo("/mock-risk-engine/evaluate"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"APPROVED\", \"reason\":\"Safe\"}")
                        .withStatus(200)));

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isConflict());
    }
}
