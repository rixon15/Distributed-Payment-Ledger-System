package org.example.paymentservice.chaos.kafka;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@SuppressWarnings("resource")
class KafkaChaosTest {
    private static final int KAFKA_PORT = 9092;
    private static final int REDIS_PORT = 6379;
    private static final int TOXIPROXY_KAFKA_PORT = 29092;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment_db")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres");

    @Container
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis");

    @Container
    static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    @Container
    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.11.0"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8474, TOXIPROXY_KAFKA_PORT);

    static Proxy kafkaProxy;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(8081))
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.kafka.bootstrap-servers", () ->
                TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_KAFKA_PORT));

        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(REDIS_PORT));

        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void setupKafkaProxy() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(
                TOXIPROXY.getHost(),
                TOXIPROXY.getControlPort()
        );

        kafkaProxy = client.createProxy(
                "kafka-proxy",
                "0.0.0.0:" + TOXIPROXY_KAFKA_PORT,
                "kafka:" + KAFKA_PORT
        );
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void resetStateAndStubDependencies() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, payments CASCADE");
        deleteByPrefix("request_lock:");
        redisTemplate.delete(List.of("payment:idempotency:set"));

        stubRiskApproved();
        stubBankApproved();
    }

    @AfterEach
    void cleanupProxy() throws Exception {
        if (kafkaProxy != null) {
            kafkaProxy.enable();
            for (var toxic : kafkaProxy.toxics().getAll()) {
                toxic.remove();
            }
        }
    }

    @Test
    void kafkaDow_requestStillAccepted_andPaymentAndOutboxPersisted() throws Exception {
        UUID senderId = UUID.randomUUID();
        String key = "kafka-down-" + UUID.randomUUID();

        kafkaProxy.disable();

        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawalRequest(key))))
                .andExpect(status().isAccepted());

        awaitPaymentCountByIdempotencyKey(key, 1, WAIT_TIMEOUT);
        assertOutboxCountBySenderAndType(senderId, PaymentType.WITHDRAWAL, 1);

        Payment payment = paymentRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(payment.getUserId()).isEqualTo(senderId);
        assertThat(payment.getType()).isEqualTo(PaymentType.WITHDRAWAL);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualByComparingTo("100.0000");

        OutboxEvent outbox = latestOutboxForSenderAndType(senderId, PaymentType.WITHDRAWAL);
        assertThat(outbox.getAggregateId()).isEqualTo(senderId.toString());
        assertThat(outbox.getEventType()).isEqualTo(PaymentType.WITHDRAWAL);
        assertThat(outbox.getPayload()).contains(payment.getId().toString());
        assertThat(outbox.getPayload()).contains("\"status\": \"PENDING\"");
    }

    @Test
    void kafkaLatency_requestStillAccepted_andPaymentAndOutboxPersisted() throws Exception {
        UUID senderId = UUID.randomUUID();
        String key = "kafka-latency-" + UUID.randomUUID();

        kafkaProxy.toxics().latency("kafka-latency", ToxicDirection.DOWNSTREAM, 2000);


        mockMvc.perform(post("/payments/execute")
                        .header("X-User-ID", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawalRequest(key))))
                .andExpect(status().isAccepted());


        awaitPaymentCountByIdempotencyKey(key, 1, WAIT_TIMEOUT);
        assertOutboxCountBySenderAndType(senderId, PaymentType.WITHDRAWAL, 1);

        Payment payment = paymentRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(payment.getUserId()).isEqualTo(senderId);
        assertThat(payment.getType()).isEqualTo(PaymentType.WITHDRAWAL);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        OutboxEvent outbox = latestOutboxForSenderAndType(senderId, PaymentType.WITHDRAWAL);
        assertThat(outbox.getPayload()).contains(payment.getId().toString());
        assertThat(outbox.getPayload()).contains("\"status\": \"PENDING\"");

    }


    private PaymentRequest withdrawalRequest(String idempotencyKey) {
        return new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.WITHDRAWAL,
                new BigDecimal("100.0000"),
                "USD"
        );
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

    private OutboxEvent latestOutboxForSenderAndType(UUID senderId, PaymentType type) {
        List<OutboxEvent> matches = outboxRepository.findAll().stream()
                .filter(e -> senderId.toString().equals(e.getAggregateId()))
                .filter(e -> e.getEventType() == type)
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .toList();

        assertThat(matches).isNotEmpty();
        return matches.getLast();
    }

    private void deleteByPrefix(String prefix) {
        var keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void awaitPaymentCountByIdempotencyKey(String key, int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                    Integer.class,
                    key
            );

            if (count != null && count == expected) {
                return;
            }

            sleep(100);
        }

        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                Integer.class,
                key
        );

        assertThat(finalCount).isEqualTo(expected);
    }

    private void assertOutboxCountBySenderAndType(UUID senderId, PaymentType type, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                senderId.toString(),
                type.name()
        );

        assertThat(count).isEqualTo(expected);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }
}
