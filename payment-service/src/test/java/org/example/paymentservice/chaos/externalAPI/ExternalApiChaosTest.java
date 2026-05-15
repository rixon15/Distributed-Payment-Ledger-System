package org.example.paymentservice.chaos.externalAPI;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.example.paymentservice.dto.PaymentRequest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
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
@ActiveProfiles("test")
@SuppressWarnings("resource")
class ExternalApiChaosTest {

    /*FIXME: These test are temporary; they will "lose meaning" once the real external APIs are introduced with the ACL layer.
            The ACL will depend on sealed internal result types
    */

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String REQUEST_LOCK_PREFIX = "request_lock:";



    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(8081))
            .build();

    private static final int REDIS_PORT = 6379;

    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment_db")
                    .withUsername("testuser")
                    .withPassword("testpass");

    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        POSTGRESQL_CONTAINER.start();
        REDIS_CONTAINER.start();
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                POSTGRESQL_CONTAINER.getJdbcUrl() + "&sslmode=disable");
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(REDIS_PORT));

        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetStateAndStubDependencies() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM payments");

        deleteByPrefix(REQUEST_LOCK_PREFIX);
        redisTemplate.delete(List.of("payment:idempotency:set"));
    }

    @Test
    void riskEngineTimeout_requestAccepted_paymentFails_andFailureOutboxEmitted() throws Exception {
        wireMock.stubFor(WireMock.post("/mock-risk-engine/evaluate")
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(6000)
                        .withStatus(500)));

        String key = "risk-timeout-" + UUID.randomUUID();
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

    @Test
    void riskEngineGarbageResponse_requestAccepted_paymentFails_andFailureOutboxEmitted() throws Exception {
        wireMock.stubFor(WireMock.post("/mock-risk-engine/evaluate")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "text/plain")
                        .withStatus(200)
                        .withBody("%%% catastrophic-garbage-response ###")));

        String key = "risk-garbage-" + UUID.randomUUID();
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

    @Test
    void bankApiTimeout_requestAccepted_paymentFails_andFailureOutboxEmitted() throws Exception {
        wireMock.stubFor(WireMock.post("/mock-bank/pay")
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(4000)
                        .withStatus(500)));

        String key = "bank-timeout-" + UUID.randomUUID();
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

    @Test
    void bankApiGarbageResponse_requestAccepted_paymentFails_andFailureOutboxEmitted() throws Exception {
        wireMock.stubFor(WireMock.post("/mock-bank/pay")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "text/plain")
                        .withStatus(200)
                        .withBody("<<< totally-not-json >>>")));

        String key = "bank-garbage-" + UUID.randomUUID();
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

    private void deleteByPrefix(String prefix) {
        var keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void awaitPaymentStatusByIdempotencyKey(String key, String expectedStatus, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM payments WHERE idempotency_key = ? ORDER BY created_at DESC LIMIT 1",
                    ps -> ps.setString(1, key),
                    rs -> rs.next() ? rs.getString("status") : null
            );

            if (expectedStatus.equals(status)) {
                return;
            }

            sleep(100);
        }

        String finalStatus = jdbcTemplate.query(
                "SELECT status FROM payments WHERE idempotency_key = ? ORDER BY created_at DESC LIMIT 1",
                ps -> ps.setString(1, key),
                rs -> rs.next() ? rs.getString("status") : null
        );

        assertThat(finalStatus).isEqualTo(expectedStatus);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    private void assertOutboxPayloadStatusBySenderAndType(UUID senderId, PaymentType type, String expectedStatus, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE aggregate_id = ?
                          AND event_type = ?
                          AND payload->'payload'->>'status' = ?
                        """,
                Integer.class,
                senderId.toString(),
                type.name(),
                expectedStatus
        );

        assertThat(count).isEqualTo(expectedCount);
    }

    private void assertNoLockForKey(String idempotencyKey) {
        var keys = redisTemplate.keys(REQUEST_LOCK_PREFIX + "*" + idempotencyKey + "*");
        assertThat(keys == null || keys.isEmpty()).isTrue();
    }

    private PaymentRequest transferRequest(String key) {
        return new PaymentRequest(
                UUID.randomUUID(),
                key,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );
    }
}
