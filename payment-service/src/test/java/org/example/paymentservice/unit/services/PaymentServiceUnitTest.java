package org.example.paymentservice.unit.services;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.core.exception.InvalidTransferException;
import org.example.paymentservice.core.exception.PaymentNotFoundException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.OutboxService;
import org.example.paymentservice.service.RequestLockService;
import org.example.paymentservice.service.implementation.PaymentServiceImp;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentStrategy transferStrategy;

    @Mock
    private PaymentStrategy depositStrategy;

    @Mock
    private PaymentStrategy withdrawalStrategy;

    @Mock
    private RestClient restClient;

    @Mock
    private TransactionTemplate tx;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RequestLockService requestLockService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private PaymentServiceImp paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImp(
                paymentRepository,
                List.of(transferStrategy, depositStrategy, withdrawalStrategy),
                restClient,
                tx,
                outboxRepository,
                objectMapper,
                requestLockService,
                outboxService
        );

        ReflectionTestUtils.setField(paymentService, "riskEngineUrl", "http://risk-engine");
    }

    @Test
    void initStrategies_shouldMapSupportedStrategies() {
        PaymentServiceImp service = new PaymentServiceImp(
                paymentRepository,
                List.of(transferStrategy, depositStrategy, withdrawalStrategy),
                restClient,
                tx,
                outboxRepository,
                objectMapper,
                requestLockService,
                outboxService
        );

        ReflectionTestUtils.setField(service, "riskEngineUrl", "http://risk-engine");

        when(transferStrategy.supports(any())).thenAnswer(inv ->
                inv.getArgument(0) == PaymentType.TRANSFER);

        when(depositStrategy.supports(any())).thenAnswer(inv ->
                inv.getArgument(0) == PaymentType.DEPOSIT);

        when(withdrawalStrategy.supports(any())).thenAnswer(inv ->
                inv.getArgument(0) == PaymentType.WITHDRAWAL);

        service.initStrategies();

        Map<PaymentType, PaymentStrategy> strategyMap =
                (Map<PaymentType, PaymentStrategy>) ReflectionTestUtils.getField(service, "strategyMap");

        assertThat(strategyMap).containsEntry(PaymentType.TRANSFER, transferStrategy);
        assertThat(strategyMap).containsEntry(PaymentType.DEPOSIT, depositStrategy);
        assertThat(strategyMap).containsEntry(PaymentType.WITHDRAWAL, withdrawalStrategy);
    }

    @Test
    void processPayment_shouldThrowWhenNoStrategyExists() {
        stubStrategySupportMatrix();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(
                UUID.randomUUID(),
                "key-1",
                PaymentType.PAYMENT, // or REFUND
                new BigDecimal("10.0000"),
                "USD"
        );

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("No strategy found for type");
    }

    @Test
    void processPayment_shouldRejectSelfTransfer() {
        stubStrategySupportMatrix();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = transferRequest("self-transfer-key", senderId);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("cannot transfer money to your own account");

        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(restClient);
        verifyNoInteractions(requestLockService);
    }

    @Test
    void processPayment_shouldThrowWhenIdempotencyKeyAlreadyExists() {
        stubStrategySupportMatrix();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("dup-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("dup-key"))
                .thenReturn(Optional.of(Payment.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(DuplicatedRequestException.class);

        verify(paymentRepository).findByIdempotencyKey("dup-key");
        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(restClient);
        verifyNoInteractions(requestLockService);
    }

    @Test
    void processPayment_shouldPersistPendingPaymentAndExecuteStrategyWhenRiskApproved() {
        stubTransactions();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.APPROVED, "ok"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("ok-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("ok-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });

        paymentService.processPayment(senderId, request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        verify(transferStrategy).execute(any(Payment.class), eq(request));
        verify(requestLockService, never()).release(anyString());

        Payment saved = paymentCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(senderId);
        assertThat(saved.getReceiverId()).isEqualTo(receiverId);
        assertThat(saved.getType()).isEqualTo(PaymentType.TRANSFER);
        assertThat(saved.getIdempotencyKey()).isEqualTo("ok-key");
        assertThat(saved.getAmount()).isEqualByComparingTo("10.0000");
        assertThat(saved.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void processPayment_shouldUseSenderAsReceiverForDeposit() {
        stubTransactions();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.APPROVED, "ok"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(
                null,
                "deposit-key",
                PaymentType.DEPOSIT,
                new BigDecimal("15.0000"),
                "USD"
        );

        when(paymentRepository.findByIdempotencyKey("deposit-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });

        paymentService.processPayment(senderId, request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        verify(depositStrategy).execute(any(Payment.class), eq(request));
        verify(requestLockService, never()).release(anyString());

        assertThat(paymentCaptor.getValue().getReceiverId()).isEqualTo(senderId);
    }

    @Test
    void processPayment_shouldUseSenderAsReceiverForWithdrawal() {
        stubTransactions();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.APPROVED, "ok"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(
                null,
                "withdrawal-key",
                PaymentType.WITHDRAWAL,
                new BigDecimal("20.0000"),
                "USD"
        );

        when(paymentRepository.findByIdempotencyKey("withdrawal-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });

        paymentService.processPayment(senderId, request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        verify(withdrawalStrategy).execute(any(Payment.class), eq(request));
        verify(requestLockService, never()).release(anyString());

        assertThat(paymentCaptor.getValue().getReceiverId()).isEqualTo(senderId);
    }

    @Test
    void processPayment_shouldTranslateUniqueConstraintViolationToDuplicatedRequestException() {
        stubTransactions();
        stubStrategySupportMatrix();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = transferRequest("dup-db-key", UUID.randomUUID());

        when(paymentRepository.findByIdempotencyKey("dup-db-key")).thenReturn(Optional.empty());

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate",
                new RuntimeException("ERROR: duplicate key value violates unique constraint uc_payments_idempotency_key SQLState: 23505")
        );

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(exception);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(DuplicatedRequestException.class);

        verify(paymentRepository).saveAndFlush(any(Payment.class));
        verifyNoInteractions(restClient);
        verify(requestLockService, never()).release(anyString());
    }

    @Test
    void processPayment_shouldRethrowNonIdempotencyDataIntegrityViolation() {
        stubTransactions();
        stubStrategySupportMatrix();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        PaymentRequest request = transferRequest("integrity-key", UUID.randomUUID());

        when(paymentRepository.findByIdempotencyKey("integrity-key")).thenReturn(Optional.empty());

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "other integrity error",
                new RuntimeException("some other database integrity issue")
        );

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(exception);

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isSameAs(exception);

        verifyNoInteractions(restClient);
        verify(requestLockService, never()).release(anyString());
    }

    @Test
    void processPayment_shouldHandleRejectedRiskByFailingPaymentSavingOutboxAndReleasingLock() {
        stubTransactions();
        stubTransactionWithoutResult();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.REJECTED, "fraud"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("risk-rejected-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("risk-rejected-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.processPayment(senderId, request);

        verify(outboxService).saveOutboxEvent(
                any(Payment.class),
                eq(TransactionStatus.FAILED),
                contains("Risk rejected: fraud")
        );
        verify(requestLockService).release("risk-rejected-key");
        verify(transferStrategy, never()).execute(any(), any());
    }

    @Test
    void processPayment_shouldTreatRiskClientExceptionAsRejectedRiskFailure() {
        stubTransactions();
        stubTransactionWithoutResult();
        stubStrategySupportMatrix();
        stubRiskException();
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("risk-error-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("risk-error-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.processPayment(senderId, request);

        verify(outboxService).saveOutboxEvent(
                any(Payment.class),
                eq(TransactionStatus.FAILED),
                contains("Risk rejected: Risk Service Unavailable")
        );
        verify(requestLockService).release("risk-error-key");
        verify(transferStrategy, never()).execute(any(), any());
    }

    @Test
    void processPayment_shouldHandleManualReview() {
        stubTransactions();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.MANUAL_REVIEW, "needs review"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("manual-review-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("manual-review-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any(Payment.class))).thenReturn("{\"status\":\"MANUAL_REVIEW\"}");

        paymentService.processPayment(senderId, request);

        verify(paymentRepository).save(argThat(payment ->
                payment.getStatus() == PaymentStatus.MANUAL_REVIEW &&
                        "needs review".equals(payment.getErrorMessage()) &&
                        payment.getUpdatedAt() != null
        ));
        verify(outboxRepository).save(any(OutboxEvent.class));
        verify(transferStrategy, never()).execute(any(), any());
        verify(requestLockService, never()).release(anyString());
    }

    @Test
    void processPayment_shouldReleaseKeyOnFail() {
        stubTransactions();
        stubStrategySupportMatrix();
        stubRiskResponse(new RiskResponse(RiskStatus.APPROVED, "ok"));
        paymentService.initStrategies();

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        PaymentRequest request = transferRequest("strategy-fail-key", receiverId);

        when(paymentRepository.findByIdempotencyKey("strategy-fail-key")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });

        doThrow(new RuntimeException("strategy failed"))
                .when(transferStrategy).execute(any(Payment.class), eq(request));

        assertThatThrownBy(() -> paymentService.processPayment(senderId, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("strategy failed");

        verify(requestLockService, atMostOnce()).release(anyString());
    }

    @Test
    void resumeProcessing_shouldThrowWhenPaymentNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.resumeProcessing(paymentId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void resumeProcessing_shouldReturnWhenPaymentIsNotResumable() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .status(PaymentStatus.AUTHORIZED)
                .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        paymentService.resumeProcessing(paymentId);

        verifyNoInteractions(restClient);
        verifyNoInteractions(transferStrategy);
        verifyNoInteractions(depositStrategy);
        verifyNoInteractions(withdrawalStrategy);
        verify(requestLockService, never()).release(anyString());
    }

    @Test
    void resumeProcessing_shouldFailPaymentWhenRiskRejected() {
        stubTransactionWithoutResult();
        stubRiskResponse(new RiskResponse(RiskStatus.REJECTED, "retry rejected"));

        UUID paymentId = UUID.randomUUID();
        Payment payment = resumableTransferPayment(paymentId, PaymentStatus.PENDING, "resume-risk-reject");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transferStrategy.supports(PaymentType.TRANSFER)).thenReturn(true);

        paymentService.resumeProcessing(paymentId);

        verify(outboxService).saveOutboxEvent(
                any(Payment.class),
                eq(TransactionStatus.FAILED),
                contains("Risk rejected: retry rejected")
        );
        verify(requestLockService).release("resume-risk-reject");
        verify(transferStrategy, never()).execute(any(), any());
    }

    @Test
    void resumeProcessing_shouldExecuteStrategyWhenRiskApproved() {
        stubRiskResponse(new RiskResponse(RiskStatus.APPROVED, "ok"));

        UUID paymentId = UUID.randomUUID();
        Payment payment = resumableTransferPayment(paymentId, PaymentStatus.RECOVERING, "resume-approved");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(transferStrategy.supports(PaymentType.TRANSFER)).thenReturn(true);

        paymentService.resumeProcessing(paymentId);

        verify(paymentRepository).findById(paymentId);
        verify(transferStrategy).supports(PaymentType.TRANSFER);
        verify(transferStrategy).execute(eq(payment), any(PaymentRequest.class));
        verify(requestLockService, never()).release(anyString());
        verify(outboxService, never()).saveOutboxEvent(any(), any(), anyString());
    }

    @Test
    void resumeProcessing_shouldThrowWhenNoSupportingStrategyExists() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = resumableTransferPayment(paymentId, PaymentStatus.PENDING, "resume-no-strategy");

        PaymentServiceImp service = new PaymentServiceImp(
                paymentRepository,
                List.of(),
                restClient,
                tx,
                outboxRepository,
                objectMapper,
                requestLockService,
                outboxService
        );

        ReflectionTestUtils.setField(service, "riskEngineUrl", "http://risk-engine");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.resumeProcessing(paymentId))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Strategy not supported");
    }

    @Test
    void claimStuckPayments_shouldMarkPaymentsRecoveringSaveThemAndReturnIds() {
        Payment p1 = Payment.builder()
                .id(UUID.randomUUID())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .build();

        Payment p2 = Payment.builder()
                .id(UUID.randomUUID())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(6))
                .build();

        when(paymentRepository.findStuckPaymentsForRecovery(any(LocalDateTime.class), eq(10)))
                .thenReturn(List.of(p1, p2));

        List<UUID> result = paymentService.claimStuckPayments(10);

        assertThat(result).containsExactly(p1.getId(), p2.getId());
        assertThat(p1.getStatus()).isEqualTo(PaymentStatus.RECOVERING);
        assertThat(p2.getStatus()).isEqualTo(PaymentStatus.RECOVERING);
        assertThat(p1.getUpdatedAt()).isNotNull();
        assertThat(p2.getUpdatedAt()).isNotNull();

        verify(paymentRepository).saveAll(List.of(p1, p2));
    }

    @Test
    void claimStuckPayments_shouldReturnEmptyListWhenNoPaymentsFound() {
        when(paymentRepository.findStuckPaymentsForRecovery(any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of());

        List<UUID> result = paymentService.claimStuckPayments(5);

        assertThat(result).isEmpty();
        verify(paymentRepository).saveAll(List.of());
    }

    private PaymentRequest transferRequest(String idempotencyKey, UUID receiverId) {
        return new PaymentRequest(
                receiverId,
                idempotencyKey,
                PaymentType.TRANSFER,
                new BigDecimal("10.0000"),
                "USD"
        );
    }

    private Payment resumableTransferPayment(UUID paymentId, PaymentStatus status, String idempotencyKey) {
        return Payment.builder()
                .id(paymentId)
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .type(PaymentType.TRANSFER)
                .amount(new BigDecimal("25.0000"))
                .currency(CurrencyType.USD)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void stubTransactions() {
        when(tx.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private void stubTransactionWithoutResult() {
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    private void stubStrategySupportMatrix() {
        when(transferStrategy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == PaymentType.TRANSFER);

        when(depositStrategy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == PaymentType.DEPOSIT);

        when(withdrawalStrategy.supports(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == PaymentType.WITHDRAWAL);
    }

    private void stubRiskResponse(RiskResponse riskResponse) {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://risk-engine/evaluate")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(RiskRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(RiskResponse.class)).thenReturn(riskResponse);
    }

    private void stubRiskException() {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://risk-engine/evaluate")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(RiskRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RuntimeException("risk service down"));
    }

}