package org.example.paymentservice;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        controlledShutdown = true,
        topics = {"transaction-events"} // Add your actual topic names here
)
@ContextConfiguration(initializers = PaymentServiceIntegrationTest.Initializer.class)
class PaymentServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @MockitoBean
    private RestClient restClient;

    private RestClient.ResponseSpec responseSpec;

    private RestClient.Builder restClientBuilder;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        // Bind the mock server to the RestClient used by the strategies
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        // Clean up database and Redis between tests
        paymentRepository.deleteAll();
        outboxRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        var uriSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
        var bodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);
        responseSpec = mock(RestClient.ResponseSpec.class); // Initialize class level mock

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + postgres.getJdbcUrl(),
                    "spring.datasource.username=" + postgres.getUsername(),
                    "spring.datasource.password=" + postgres.getPassword(),
                    "spring.data.redis.host=" + redis.getHost(),
                    "spring.data.redis.port=" + redis.getMappedPort(6379),
                    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                    "app.risk-engine.url=http://localhost:8081",
                    "app.ledger-service.url=http://localhost:8080",
                    "app.bank-api.url=http://localhost:8081"
            ).applyTo(context.getEnvironment());
        }
    }

    @Test
    @DisplayName("Internal Transfer - Happy Path")
    void testInternalTransfer_Success() {
        UUID senderId = UUID.randomUUID();
        PaymentRequest paymentRequest = new PaymentRequest(UUID.randomUUID(), "key-transfer",
                TransactionType.TRANSFER, new BigDecimal("50.00"), "USD");

        mockServer.expect(requestTo(containsString("/evaluate")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));

        paymentService.processPayment(senderId, paymentRequest);

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentRepository.findAll().getFirst().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(outboxRepository.findAll().getFirst().getEventType()).isEqualTo("TRANSACTION_INITIATED");
    }

    @Test
    @DisplayName("Deposit - Bank Approved")
    void testDeposit_Success() {
        UUID userId = UUID.randomUUID();
        PaymentRequest paymentRequest = new PaymentRequest(null, "key-deposit",
                TransactionType.DEPOSIT, new BigDecimal("200.00"), "USD");

        when(responseSpec.body(any(Class.class)))
                .thenReturn(new RiskResponse(RiskStatus.APPROVED, "Passed")) // Adjust to your actual Risk DTO
                .thenReturn(new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.APPROVED, "SUCCESS"));

        paymentService.processPayment(userId, paymentRequest);

        Payment payment = paymentRepository.findAll().getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("Withdrawal Saga - Complete Flow")
    void testWithdrawal_Success() {
        UUID userId = UUID.randomUUID();
        PaymentRequest paymentRequest = new PaymentRequest(null, "key-withdrawal",
                TransactionType.WITHDRAWAL, new BigDecimal("100.00"), "USD");

        when(responseSpec.body(RiskResponse.class))
                .thenReturn(new RiskResponse(RiskStatus.APPROVED, "Passed"));
        when(responseSpec.toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());
        when(responseSpec.body(BankPaymentResponse.class))
                .thenReturn(new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.APPROVED, "SUCCESS"));

        paymentService.processPayment(userId, paymentRequest);

        Payment payment = paymentRepository.findAll().getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("Idempotency - Second request should be ignored")
    void testIdempotency_RedisLock() {
        UUID userId = UUID.randomUUID();
        PaymentRequest paymentRequest = new PaymentRequest(UUID.randomUUID(), "duplicated-key",
                TransactionType.TRANSFER, new BigDecimal("10.00"), "USD");

        mockServer.expect(requestTo(anyString()))
                .andRespond(withSuccess("{\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));

        paymentService.processPayment(userId, paymentRequest);

        assertThatThrownBy(() -> paymentService.processPayment(userId, paymentRequest))
                .isInstanceOf(DuplicatedRequestException.class);

        assertThat(paymentRepository.count()).isEqualTo(1);
    }
}
