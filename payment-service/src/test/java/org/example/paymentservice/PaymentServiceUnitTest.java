package org.example.paymentservice;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.TransactionType;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    private PaymentServiceImp paymentService;

    private List<PaymentStrategy> strategies;
    private UUID senderId;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        strategies = new ArrayList<>();
        strategies.add(depositStrategy);
        strategies.add(transferStrategy);

        // Use reflection or constructor to inject the strategies list if @InjectMocks doesn't handle it
        paymentService = new PaymentServiceImp(paymentRepository, strategies, redisTemplate, restClient, transactionTemplate);

        senderId = UUID.randomUUID();
        request = new PaymentRequest(UUID.randomUUID(), "unique-key-123", TransactionType.TRANSFER, new BigDecimal("100.00"), "USD");

        // Mock TransactionTemplate to execute the callback immediately
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }


    @Test
    @DisplayName("Should process payment successfully when it's a new request")
    void testProcessPayment_Success() {
        mockRedisAcquire(true);
        when(transferStrategy.supports(TransactionType.TRANSFER)).thenReturn(true);
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
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .userId(senderId)
                .idempotencyKey("resume-key")
                .type(TransactionType.DEPOSIT)
                .amount(new BigDecimal("50.00"))
                .currency(CurrencyType.USD)
                .build();

        when(depositStrategy.supports(TransactionType.DEPOSIT)).thenReturn(true);
        mockRiskEngine(RiskStatus.APPROVED);

        paymentService.resumeProcessing(payment);

        verify(depositStrategy).execute(any(Payment.class), any(PaymentRequest.class));
    }

    @Test
    @DisplayName("Should throw exception if no strategy supports the type")
    void testProcessPayment_NoStrategy() {
        mockRedisAcquire(true);
        when(depositStrategy.supports(any())).thenReturn(false);
        when(transferStrategy.supports(any())).thenReturn(false);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should block execution and release lock if Risk Engine REJECTS")
    void testProcessPayment_RiskRejected() {
        mockRedisAcquire(true);
        mockRiskEngine(RiskStatus.REJECTED);

        paymentService.processPayment(senderId, request);

        verifyNoInteractions(transferStrategy);
    }


    private void mockRedisAcquire(boolean success) {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(success);
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
}
