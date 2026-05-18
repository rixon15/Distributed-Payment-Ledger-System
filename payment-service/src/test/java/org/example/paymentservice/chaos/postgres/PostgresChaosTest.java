package org.example.paymentservice.chaos.postgres;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@SuppressWarnings("resource")
@ActiveProfiles("test")
class PostgresChaosTest {

    private static final String TOPIC = "transaction.response";

    private static final int POSTGRES_PORT = 5432;
    private static final int TOXIPROXY_POSTGRES_PORT = 8666;

    static final Network NETWORK = Network.newNetwork();

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("test")
            .withPassword("test")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    @Container
    static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.0")
    );

    @Container
    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.11.0"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8474, TOXIPROXY_POSTGRES_PORT);

    static Proxy postgresProxy;

    @BeforeAll
    static void setup() throws Exception {

        ToxiproxyClient client = new ToxiproxyClient(
                TOXIPROXY.getHost(),
                TOXIPROXY.getControlPort()
        );

        postgresProxy = client.createProxy(
                "postgres-proxy",
                "0.0.0.0:" + TOXIPROXY_POSTGRES_PORT,
                "postgres:" + POSTGRES_PORT

        );


    }

    @AfterEach
    void cleanupProxy() throws Exception {
        if (postgresProxy != null) {
            postgresProxy.enable();
            for (var toxic : postgresProxy.toxics().getAll()) {
                toxic.remove();
            }
        }
    }

    @BeforeEach
    void setupWireMockStubs() {
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

        wireMockServer.stubFor(WireMock.get(WireMock.urlPathMatching("/mock-bank/status/.*"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                  "transactionId": "3f8c2e4a-9b6d-4d91-8c7a-2f5b1e0d6a3f",
                                  "status": "NOT_FOUND",
                                  "reasonCode": "TRANSACTION NOT PROCESSED YET"
                                }
                                """)));
    }


    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://%s:%d/%s?socketTimeout=3".formatted(
                        TOXIPROXY.getHost(),
                        TOXIPROXY.getMappedPort(TOXIPROXY_POSTGRES_PORT),
                        POSTGRESQL_CONTAINER.getDatabaseName()
                )
        );

        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id",
                () -> "chaos-test-" + UUID.randomUUID());
        registry.add("app.bank.url", () ->
                "http://localhost:" + wireMockServer.getPort() + "/mock-bank");
        registry.add("app.risk-engine.url", () ->
                "http://localhost:" + wireMockServer.getPort() + "/mock-risk-engine");
    }

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void shouldKeepPaymentPendingDuringPostgresOutageAndAuthorizeAfterRecovery() throws Exception {

        UUID senderId = UUID.randomUUID();

        Payment payment = Payment.builder()
                .userId(senderId)
                .receiverId(senderId)
                .type(PaymentType.WITHDRAWAL)
                .idempotencyKey("idem-" + UUID.randomUUID())
                .amount(new BigDecimal("100.0000"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.PENDING)
                .errorMessage(null)
                .build();

        UUID paymentId = paymentRepository.saveAndFlush(payment).getId();

        long outboxCount = outboxRepository.count();

        String message = objectMapper.writeValueAsString(Map.of(
                "type", "WITHDRAWAL_RESERVE",
                "referenceId", paymentId.toString()
        ));

        cutPostgresFor(Duration.ofSeconds(2), () ->
                kafkaTemplate.send(TOPIC, paymentId.toString(), message).get()
        );

        postgresProxy.enable();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Payment reloadedPayment = paymentRepository.findById(paymentId).orElseThrow();
                    long reloadedOutboxCount = outboxRepository.count();

                    assertThat(reloadedPayment.getStatus()).isEqualByComparingTo(PaymentStatus.AUTHORIZED);
                    assertThat(reloadedPayment.getErrorMessage()).isNull();
                    assertThat(outboxCount).isZero();
                    assertThat(reloadedOutboxCount).isEqualTo(1);

                    assertOutboxPayloadStatusBySenderAndType(senderId, PaymentType.WITHDRAWAL, TransactionStatus.POSTED.name(), 1);
                });
    }

    @Test
    void shouldFailFastWhenPostgresExperiencesSevereLatency() throws Exception {
        postgresProxy.toxics().latency("postgres-latency", ToxicDirection.DOWNSTREAM, 3000);

        try {
            Payment payment = Payment.builder()
                    .userId(UUID.randomUUID())
                    .receiverId(UUID.randomUUID())
                    .type(PaymentType.TRANSFER)
                    .idempotencyKey("idem-" + UUID.randomUUID())
                    .amount(new BigDecimal("100.0000"))
                    .currency(CurrencyType.USD)
                    .status(PaymentStatus.PENDING)
                    .build();

            assertThrows(Exception.class, () -> {
                paymentRepository.saveAndFlush(payment);
            });
        } finally {
            postgresProxy.toxics().get("postgres-latency").remove();
        }

    }

    private void cutPostgresFor(Duration duration, ThrowingRunnable action) throws Exception {
        postgresProxy.disable();
        try {
            action.run();
            Thread.sleep(duration.toMillis());
        } finally {
            postgresProxy.enable();
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void assertOutboxPayloadStatusBySenderAndType(
            UUID senderId,
            PaymentType type,
            String expectedStatus,
            int expectedCount
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE aggregate_id = ?
                          AND event_type = ?
                          AND payload->'payload'->>'senderId' = ?
                          AND payload->'payload'->>'status' = ?
                        """,
                Integer.class,
                senderId.toString(),
                type.name(),
                senderId.toString(),
                expectedStatus
        );

        assertThat(count).isEqualTo(expectedCount);
    }
}
