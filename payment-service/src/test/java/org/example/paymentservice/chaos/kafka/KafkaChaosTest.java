package org.example.paymentservice.chaos.kafka;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.example.paymentservice.dto.PaymentRequest;
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
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
@SuppressWarnings("resource")
@ActiveProfiles("test")
public class KafkaChaosTest {

    private static final int KAFKA_PORT = 9092;
    private static final int TOXIPROXY_KAFKA_PORT = 29092;

    static final Network NETWORK = Network.newNetwork();

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.0")
    ).withNetwork(NETWORK).withNetworkAliases("kafka");

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.11.0")
    ).withNetwork(NETWORK).withExposedPorts(8474, TOXIPROXY_KAFKA_PORT);

    static Proxy kafkaProxy;

    @BeforeAll
    static void setupProxy() throws Exception {
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

    @AfterEach
    void cleanupProxy() throws Exception {
        if (kafkaProxy != null) {
            kafkaProxy.enable();
            for (var toxic : kafkaProxy.toxics().getAll()) {
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
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.kafka.bootstrap-servers", () ->
                TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_KAFKA_PORT));

        registry.add("app.bank.url", () ->
                "http://localhost:" + wireMockServer.getPort() + "/mock-bank");
        registry.add("app.risk-engine.url", () ->
                "http://localhost:" + wireMockServer.getPort() + "/mock-risk-engine");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void kafkaDown_outboxProof_apiStillAcceptedAndOutboxEventEventuallyDrains() throws Exception {
        long paymentsBefore = paymentRepository.count();
        long outboxBefore = outboxRepository.count();

        kafkaProxy.disable();

        UUID senderId = UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", String.valueOf(senderId));

        PaymentRequest request = new PaymentRequest(
                null,
                idempotencyKey,
                PaymentType.WITHDRAWAL,
                new BigDecimal("100.0000"),
                "USD"
        );

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/payments/execute",
                new HttpEntity<>(request, headers),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(paymentRepository.count()).isEqualTo(paymentsBefore + 1);
            assertThat(outboxRepository.count()).isEqualTo(outboxBefore + 1);
        });

        kafkaProxy.enable();

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Payment p = paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
                    assertThat(p.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

                    // Option A: assert by event types in outbox payload/event_type
                    long eventCountForPayment = outboxRepository.findAll().stream()
                            .filter(e -> senderId.toString().equals(e.getAggregateId()))
                            .count();
                    assertThat(eventCountForPayment).isGreaterThanOrEqualTo(2);
                });
    }
}
