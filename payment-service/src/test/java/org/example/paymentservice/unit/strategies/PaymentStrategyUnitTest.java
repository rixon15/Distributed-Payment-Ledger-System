package org.example.paymentservice.unit.strategies;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.OutboxService;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentStrategyUnitTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TransactionTemplate tx;
    @Mock
    private RestClient restClient;
    @Mock
    private OutboxService outboxService;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private TestPaymentStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TestPaymentStrategy(paymentRepository, objectMapper, tx, restClient, outboxService);
    }

    @Test
    void handleFailure_shouldMarkFailedSaveAndEmitFailedOutbox() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();

        strategy.callHandleFailure(payment, "Bank Declined", "DECLINED_BY_BANK");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Declined");

        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "DECLINED_BY_BANK");
    }

    @Test
    void finalizeStatus_shouldMapAuthorizedToPostedAndSaveExternalId() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        UUID externalId = UUID.randomUUID();

        strategy.callFinalizeStatus(payment, PaymentStatus.AUTHORIZED, externalId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isEqualTo(externalId.toString());

        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
    }

    @Test
    void finalizeStatus_shouldMapFailedToFailedAndNullExternalId() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();

        strategy.callFinalizeStatus(payment, PaymentStatus.FAILED, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getExternalTransactionId()).isNull();

        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, null);
    }

    @Test
    void finalizeStatus_shouldMapOtherStatusesToPending() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();

        strategy.callFinalizeStatus(payment, PaymentStatus.PENDING, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.PENDING, null);
    }

    @Test
    void reconcileWithBank_shouldHandleNullBankResponseAsFailure() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();

        strategy.callReconcileWithBank(payment, null, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Service Unavailable");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Failed to reach bank service");
    }

    @Test
    void reconcileWithBank_shouldFinalizeAuthorizedWhenApproved() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        UUID txId = UUID.randomUUID();
        BankPaymentResponse response = new BankPaymentResponse(txId, BankPaymentStatus.APPROVED, "OK");

        strategy.callReconcileWithBank(payment, response, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isEqualTo(txId.toString());
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
    }

    @Test
    void reconcileWithBank_shouldFailWhenDeclined() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        BankPaymentResponse response =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.DECLINED, "LIMIT_EXCEEDED");

        strategy.callReconcileWithBank(payment, response, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Declined");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "LIMIT_EXCEEDED");
    }

    @Test
    void reconcileWithBank_shouldDoNothingWhenPending() {
        Payment payment = samplePayment();
        BankPaymentResponse response =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.PENDING, "WAIT");

        strategy.callReconcileWithBank(payment, response, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(outboxService, never()).saveOutboxEvent(any(), any(), any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(tx);
    }

    @Test
    void reconcileWithBank_shouldFailWhenNotFoundAndRetryExceeded() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        BankPaymentResponse response =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.NOT_FOUND, "MISSING");

        strategy.callReconcileWithBank(payment, response, "http://mock-bank", 4);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Unavailable");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Max retries exceeded");
    }

    @Test
    void reconcileWithBank_shouldExecuteNewPaymentWhenNotFoundAndRetryAllowed_thenApprove() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        BankPaymentResponse first =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.NOT_FOUND, "MISSING");
        UUID approvedTx = UUID.randomUUID();
        BankPaymentResponse second =
                new BankPaymentResponse(approvedTx, BankPaymentStatus.APPROVED, "OK");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://mock-bank/pay")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(BankPaymentRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(BankPaymentResponse.class)).thenReturn(second);

        strategy.callReconcileWithBank(payment, first, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isEqualTo(approvedTx.toString());
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
    }

    @Test
    void reconcileWithBank_shouldFailWhenExecuteNewPaymentThrows() {
        stubTxAndPaymentSave();
        Payment payment = samplePayment();
        BankPaymentResponse first =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.NOT_FOUND, "MISSING");

        when(restClient.post()).thenThrow(new RuntimeException("bank down"));

        strategy.callReconcileWithBank(payment, first, "http://mock-bank", 0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).startsWith("Bank API Error:");
        verify(outboxService).saveOutboxEvent(
                eq(payment),
                eq(TransactionStatus.FAILED),
                eq("Bank service unreachable or malformed response")
        );
    }

    private void stubTxAndPaymentSave() {
        doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Payment samplePayment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .type(PaymentType.TRANSFER)
                .idempotencyKey("ps-key")
                .amount(new BigDecimal("99.9900"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static class TestPaymentStrategy extends PaymentStrategy {

        TestPaymentStrategy(
                PaymentRepository paymentRepository,
                ObjectMapper objectMapper,
                TransactionTemplate tx,
                RestClient restClient,
                OutboxService outboxService
        ) {
            super(paymentRepository, objectMapper, tx, restClient, outboxService);
        }

        @Override
        public boolean supports(PaymentType type) {
            return type == PaymentType.TRANSFER;
        }

        @Override
        public void execute(Payment payment, PaymentRequest request) {
            // not needed for these tests
        }

        void callHandleFailure(Payment payment, String internalReason, String userMessage) {
            handleFailure(payment, internalReason, userMessage);
        }

        void callFinalizeStatus(Payment payment, PaymentStatus status, UUID externalId) {
            finalizeStatus(payment, status, externalId);
        }

        void callReconcileWithBank(Payment payment, BankPaymentResponse response, String bankUrl, int retryCount) {
            reconcileWithBank(payment, response, bankUrl, retryCount);
        }
    }
}