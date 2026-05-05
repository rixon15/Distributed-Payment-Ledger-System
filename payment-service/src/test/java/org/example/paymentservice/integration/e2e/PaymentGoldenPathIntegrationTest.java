package org.example.paymentservice.integration.e2e;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.integration.base.AbstractIntegrationTest;
import org.example.paymentservice.model.Payment;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentGoldenPathIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String IDEMPOTENCY_KEY = "payment:idempotency:set";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().port(8081))
            .build();

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, payments CASCADE");
        redisTemplate.delete(List.of(IDEMPOTENCY_KEY));
        deleteByPrefix("request_lock:");

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
    void successfulTransfer_persistsPayment_andWritesToOutbox() throws Exception {
        String idempotencyKey = "transfer-" + UUID.randomUUID();
        UUID senderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID receiverId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        PaymentRequest request = new PaymentRequest(
                receiverId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("100.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-user-ID", senderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertPaymentRow(senderId, receiverId, PaymentType.TRANSFER, idempotencyKey, "100.0000", "USD");
        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }

    @Test
    void successfulDeposit_persistsPayment_andWritesToOutbox() throws Exception {
        String idempotencyKey = "deposit-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.DEPOSIT,
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertPaymentRow(senderId, senderId, PaymentType.DEPOSIT, idempotencyKey, "50.0000", "USD");
        assertOutboxCountBySenderAndType(senderId, PaymentType.DEPOSIT, 1);
    }

    @Test
    void successfulWithdrawal_persistsPayment_andWritesToOutbox() throws Exception {
        String idempotencyKey = "withdrawal-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.WITHDRAWAL,
                new BigDecimal("10.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertPaymentRow(senderId, senderId, PaymentType.WITHDRAWAL, idempotencyKey, "10.0000", "USD");
        assertOutboxCountBySenderAndType(senderId, PaymentType.WITHDRAWAL, 1);
    }

    @Test
    void duplicateIdempotency_senderCallIsConflict_andNoDuplicateRows() throws Exception {
        String idempotencyKey = "duplicate-" + UUID.randomUUID();
        UUID senderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID receiverId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        PaymentRequest request = new PaymentRequest(
                receiverId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("100.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertPaymentRow(senderId, receiverId, PaymentType.TRANSFER, idempotencyKey, "100.0000", "USD");
        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }


    private void assertPaymentRow(UUID senderId, UUID receiverId, PaymentType type,
                                  String idempotencyKey, String amount, String currency) {

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM payments
                        WHERE user_id = ?
                          AND receiver_id IS NOT DISTINCT FROM ?
                          AND type = ?
                          AND idempotency_key = ?
                          AND amount = ?
                          AND currency = ?
                        """,
                Integer.class,
                senderId,
                receiverId,
                type.name(),
                idempotencyKey,
                new BigDecimal(amount),
                currency
        );

        assertThat(count).isEqualTo(1);

    }

}
