package org.example.paymentservice.integration.idempotency;

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
import java.util.concurrent.*;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentIdempotencyIntegrationTest extends AbstractIntegrationTest {

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
    static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().port(8081))
            .build();

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
    void sequentialDuplicate_sameIdempotencyKey_createSinglePaymentAndSingleOutbox() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

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
        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }

    @Test
    void concurrentDuplicate_sameIdempotencyKey_createsSinglePaymentAndSingleOutbox() throws InterruptedException, ExecutionException, TimeoutException {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                receiverId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("100.0000"),
                "USD"
        );

        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                boolean started = start.await(5, TimeUnit.SECONDS);
                assertThat(started).isTrue();

                var result = mockMvc.perform(post("/payments/execute")
                                .header("X-User-ID", senderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andReturn();

                return result.getResponse().getStatus();
            }));
        }

        boolean allReady = ready.await(5, TimeUnit.SECONDS);
        assertThat(allReady).isTrue();
        start.countDown();

        int successCount = 0;
        int conflictCount = 0;

        for (Future<Integer> future : futures) {

            Integer status = future.get(10, TimeUnit.SECONDS);

            if (status >= 200 && status < 300) {
                successCount++;
            } else if (status == 409) {

                conflictCount++;
            }

        }

        pool.shutdownNow();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(9);
        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }

    @Test
    void sameIdempotencyKey_differentPayload_isRejectedAsDuplicate() throws Exception {
        String idempotencyKey = "idme-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest first = new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );

        PaymentRequest second = new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-Id", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-Id", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
        assertOutboxCountBySenderAndType(senderId, PaymentType.TRANSFER, 1);
    }

    @Test
    void sameIdempotencyKey_differentPaymentType_isRejectedAsDuplicate() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PaymentRequest transfer = new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );

        PaymentRequest deposit = new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.DEPOSIT,
                new BigDecimal("10.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deposit)))
                .andExpect(status().isConflict());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
    }

    @Test
    void sameIdempotencyKey_differentSenderHeader_isRejectedAsDuplicate() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        UUID senderA = UUID.randomUUID();
        UUID senderB = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("100.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
    }

    @Test
    void differentIdempotencyKeys_samePayload_createsTwoPayments() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        PaymentRequest first = new PaymentRequest(
                receiver,
                "idem-" + UUID.randomUUID(),
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );

        PaymentRequest second = new PaymentRequest(
                receiver,
                "idem-" + UUID.randomUUID(),
                PaymentType.TRANSFER,
                new BigDecimal("25.0000"),
                "USD"
        );

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", sender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", sender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().is2xxSuccessful());

        awaitPaymentCountByIdempotencyKey(first.idempotencyKey(), 1, WAIT_TIMEOUT);
        awaitPaymentCountByIdempotencyKey(second.idempotencyKey(), 1, WAIT_TIMEOUT);

        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void highContention_50ConcurrentRequests_sameIdempotencyKey_stillSingleInsert() throws InterruptedException, ExecutionException, TimeoutException {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        PaymentRequest request = new PaymentRequest(
                receiverId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("25.0000"),
                "USD"
        );

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/payments/execute")
                                .header("X-User-ID", senderId.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int success = 0;
        int conflict = 0;

        for (Future<Integer> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS);
            if (status >= 200 && status < 300) success++;
            if (status == 409) conflict++;
        }

        pool.shutdownNow();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);

        awaitPaymentCountByIdempotencyKey(idempotencyKey, 1, WAIT_TIMEOUT);
    }
}
