package org.example.paymentservice.integration.resilience;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.integration.base.AbstractIntegrationTest;
import org.example.paymentservice.model.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentResilienceIntegrationTest extends AbstractIntegrationTest {


    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String IDEMPOTENCY_KEY = "payment:idempotency:set";
    private static final String REQUEST_LOCK_PREFIX = "request_lock:";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(8081))
            .build();

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, payments CASCADE");
        redisTemplate.delete(List.of(IDEMPOTENCY_KEY));
        deleteByPrefix(REQUEST_LOCK_PREFIX);

        // default healthy stubs
        stubRiskApproved();
        stubBankApproved();
    }

    @Test
    void riskEngineRejects_requestAccepted_paymentFails_andFailureOutboxEmitted() throws Exception {
        wireMock.stubFor(WireMock.post("/mock-risk-engine/evaluate")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody(
                                """
                                 {
                                    "status": "REJECTED",
                                    "reason": "High risk of fraud detected"
                                 }
                                """
                        )));

        String key = "risk-reject-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        mockMvc.perform(post("/payments/execute")
                .header("X-User-ID", senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transferRequest(key))))
                .andExpect(status().isAccepted());

        awaitPaymentStatusByIdempotencyKey(key, "FAILED", WAIT_TIMEOUT);
        assertOutboxPayloadStatusBySenderAndType(senderId, PaymentType.TRANSFER, "FAILED", 1);
        assertNoLockForKey(key);
    }

    private void stubRiskApproved() {
        wireMock.stubFor(WireMock.post("/mock-risk-engine/evaluate")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                    "status": "APPROVED",
                                    "reason": "OK"
                                }
                                """)));
    }

    private void stubBankApproved() {
        wireMock.stubFor(WireMock.post("/mock-bank/pay")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                    "transactionID":"%s",
                                    "status":"APPROVED",
                                    "reason":"SUCCESS"
                                }
                                """.formatted(UUID.randomUUID()))));
    }

    private void assertNoLockForKey(String idempotencyKey) {
        var keys = redisTemplate.keys(REQUEST_LOCK_PREFIX + "*" + idempotencyKey + "*");
        assertThat(keys == null || keys.isEmpty()).isTrue();
    }

    private PaymentRequest transferRequest(String idempotencyKey) {
        return new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );
    }

    private PaymentRequest withdrawalRequest(String idempotencyKey) {
        return new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.WITHDRAWAL,
                new BigDecimal("10.0000"),
                "USD"
        );
    }
}
