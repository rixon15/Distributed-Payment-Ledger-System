package org.example.paymentservice;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.implementation.PaymentServiceImp;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private RestClient restClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private PaymentStrategy depositStrategy;
    @Mock
    private PaymentStrategy transferStrategy;

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private ObjectMapper objectMapper;

    private PaymentServiceImp paymentService;

    private UUID senderId;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        List<PaymentStrategy> strategies = new ArrayList<>();
        strategies.add(depositStrategy);
        strategies.add(transferStrategy);

        lenient().when(depositStrategy.supports(TransactionType.DEPOSIT)).thenReturn(true);
        lenient().when(transferStrategy.supports(TransactionType.TRANSFER)).thenReturn(true);

        paymentService = new PaymentServiceImp(
                paymentRepository,
                strategies,
                redisTemplate,
                restClient,
                transactionTemplate,
                outboxRepository,
                objectMapper
        );

        paymentService.initStrategies();

        Mockito.clearInvocations(depositStrategy, transferStrategy, redisTemplate);

        senderId = UUID.randomUUID();
        request = new PaymentRequest(UUID.randomUUID(), "unique-key-123", TransactionType.TRANSFER, new BigDecimal("100.00"), "USD");

        setupTransactionMocks();
    }


    @Test
    @DisplayName("Should process payment successfully when it's a new request")
    void testProcessPayment_Success() {
        mockRedisAcquire(true);

        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        mockRiskEngine(RiskStatus.APPROVED);

        paymentService.processPayment(senderId, request);

        verify(paymentRepository).save(argThat(p -> p.getIdempotencyKey().equals("unique-key-123")));
        verify(transferStrategy).execute(any(Payment.class), eq(request));
    }

    @Test
    @DisplayName("Should throw DuplicatedRequestException when Redis lock fails")
    void testProcessPayment_Duplicate() {
        mockRedisAcquire(false);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(DuplicatedRequestException.class);

        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(transferStrategy);
    }

    @Test
    @DisplayName("Should resume processing using correct strategy")
    void testResumeProcessing() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .userId(senderId)
                .idempotencyKey("resume-key")
                .type(TransactionType.DEPOSIT)
                .amount(new BigDecimal("50.00"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.RECOVERING) // <--- Important for your check
                .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        mockRiskEngine(RiskStatus.APPROVED);

        paymentService.resumeProcessing(paymentId);

        verify(depositStrategy).execute(any(Payment.class), any());
    }

    @Test
    @DisplayName("Should throw exception if no strategy supports the type")
    void testProcessPayment_NoStrategy() {
        mockRedisAcquire(true);

        PaymentRequest invalidRequest = new PaymentRequest(UUID.randomUUID(), "key", TransactionType.WITHDRAWAL, BigDecimal.TEN, "USD");

        mockRiskEngine(RiskStatus.APPROVED);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, invalidRequest))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should update status to FAILED and exit gracefully if Risk Engine REJECTS")
    void testProcessPayment_RiskRejected() {
        mockRedisAcquire(true);
        mockRiskEngine(RiskStatus.REJECTED);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.processPayment(senderId, request);

        verify(transferStrategy, never()).execute(any(), any());
        verify(paymentRepository, times(2)).save(any(Payment.class)); // Created + Updated
        verify(redisTemplate).delete(request.idempotencyKey());
    }


    private void mockRedisAcquire(boolean success) {
        lenient().when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(success);
    }

    private void mockRiskEngine(RiskStatus status) {
        var requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        var requestBodySpec = mock(RestClient.RequestBodySpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(RiskRequest.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.body(RiskResponse.class)).thenReturn(new RiskResponse(status, "Verified"));
    }

    private void setupTransactionMocks() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }
}
