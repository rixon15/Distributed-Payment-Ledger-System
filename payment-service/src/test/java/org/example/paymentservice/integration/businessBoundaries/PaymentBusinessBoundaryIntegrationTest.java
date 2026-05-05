package org.example.paymentservice.integration.businessBoundaries;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PaymentBusinessBoundaryIntegrationTest extends AbstractIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "payment:idempotency:set";
    private static final String REQUEST_LOCK_PREFIX = "request_lock:";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance().options(wireMockConfig().port(8081)).build();

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, payments CASCADE");
        redisTemplate.delete(List.of(IDEMPOTENCY_KEY));
        deleteByPrefix(REQUEST_LOCK_PREFIX);

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/mock-risk-engine/evaluate"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                  "status": "APPROVED",
                                  "reason": "Risk score is within acceptable limits"
                                }
                                """)));

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/mock-bank/pay"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                  "transactionId": "3f8c2e4a-9b6d-4d91-8c7a-2f5b1e0d6a3f",
                                  "status": "APPROVED",
                                  "reason": "SUCCESS"
                                }
                                """)));
    }

    @Test
    void transfer_missing_receiver_isRejected_andNoPaymentOrOutboxPersisted() throws Exception {
        String idempotencyKey = "fail-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(null, idempotencyKey, PaymentType.TRANSFER, new BigDecimal("10.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isBadRequest());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 0, WAIT_TIMEOUT);
        assertOutboxCount(0);
    }

    @Test
    void zeroAmount_isRejected_andNoPaymentOrOutboxPersisted() throws Exception {
        String idempotencyKey = "zero-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(null, idempotencyKey, PaymentType.TRANSFER, new BigDecimal("0.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isBadRequest());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 0, WAIT_TIMEOUT);
        assertOutboxCount(0);
    }

    @Test
    void negativeAmount_isRejected_andNoPaymentOrOutboxPersisted() throws Exception {
        String idempotencyKey = "neg-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), idempotencyKey, PaymentType.TRANSFER, new BigDecimal("-10.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isBadRequest());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 0, WAIT_TIMEOUT);
        assertOutboxCount(0);
    }

    @Test
    void duplicatedIdempotencyKey_secondRequestDoesNotCreateSecondPayment() throws Exception {
        String idempotencyKey = "transfer-unique-key-" + UUID.randomUUID();
        UUID senderId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentRequest request = new PaymentRequest(UUID.fromString("22222222-2222-2222-2222-222222222222"), idempotencyKey, PaymentType.TRANSFER, new BigDecimal("100.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isConflict());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);

        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }

    @Test
    void deposit_receiverNull_isAccepted() throws Exception {
        String idempotencyKey = "deposit-unique-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(null, idempotencyKey, PaymentType.DEPOSIT, new BigDecimal("50.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
    }

    @Test
    void withdrawal_receiverNull_isAccepted() throws Exception {
        String idempotencyKey = "withdrawal-unique-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(null, idempotencyKey, PaymentType.WITHDRAWAL, new BigDecimal("10.0000"), "USD");

        mockMvc.perform(post("/payments/execute").header("X-User-ID", senderId.toString()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
    }

    @Test
    void transfer_toSelf_isRejectedSynchronously_andNoMutation() throws Exception {
        String idempotencyKey = "self-key-" + UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                userId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("25.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()); // Should fail fast

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 0, WAIT_TIMEOUT);
        assertOutboxCount(0);
    }

    @Test
    void missingIdempotencyKey_isRejectedSynchronously() throws Exception {
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(),
                null,
                PaymentType.TRANSFER,
                new BigDecimal("25.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        assertOutboxCount(0);
    }

    @Test
    void riskEngineRejects_paymentFailsSynchronously_andFailureOutboxEventEmitted() throws Exception {
        String idempotencyKey = "risk-reject-key-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/mock-risk-engine/evaluate"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "reason": "High risk of fraud detected"
                                }
                                """)));

        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("5000.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        assertOutboxCount(1);
    }

}
