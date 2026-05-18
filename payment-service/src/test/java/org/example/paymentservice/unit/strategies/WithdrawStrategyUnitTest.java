package org.example.paymentservice.unit.strategies;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.OutboxService;
import org.example.paymentservice.service.strategy.WithdrawStrategy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawStrategyUnitTest {

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
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock
    private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private WithdrawStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new WithdrawStrategy(
                paymentRepository,
                objectMapper,
                tx,
                restClient,
                "http://mock-bank",
                outboxService
        );
    }

    @Test
    void supports_shouldReturnTrueOnlyForWithdrawal() {
        assertThat(strategy.supports(PaymentType.WITHDRAWAL)).isTrue();
        assertThat(strategy.supports(PaymentType.DEPOSIT)).isFalse();
        assertThat(strategy.supports(PaymentType.TRANSFER)).isFalse();
        assertThat(strategy.supports(PaymentType.PAYMENT)).isFalse();
        assertThat(strategy.supports(PaymentType.REFUND)).isFalse();
    }

    @Test
    void execute_shouldFinalizeAsPendingAndEmitPendingOutbox() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        PaymentRequest request = sampleRequest();

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getExternalTransactionId()).isNull();

        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.PENDING, null);
    }

    @Test
    void reconcilePaymentWithBank_shouldAuthorizeWhenBankApproved() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        UUID bankTx = UUID.randomUUID();
        BankPaymentResponse bankResponse = new BankPaymentResponse(bankTx, BankPaymentStatus.APPROVED, "OK");

        stubBankStatusGet(payment.getId().toString(), bankResponse);

        strategy.reconcilePaymentWithBank(payment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isEqualTo(bankTx.toString());
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
    }

    @Test
    void reconcilePaymentWithBank_shouldFailWhenBankDeclined() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        BankPaymentResponse bankResponse =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.DECLINED, "INSUFFICIENT_FUNDS");

        stubBankStatusGet(payment.getId().toString(), bankResponse);

        strategy.reconcilePaymentWithBank(payment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Declined");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "INSUFFICIENT_FUNDS");
    }

    @Test
    void reconcilePaymentWithBank_shouldFailWhenBankStatusIsNull() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();

        stubBankStatusGet(payment.getId().toString(), null);

        strategy.reconcilePaymentWithBank(payment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Service Unavailable");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Failed to reach bank service");
    }

    @Test
    void reconcilePaymentWithBank_shouldNotThrowWhenBankGetThrowsAndShouldFailViaNullPath() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("http://mock-bank/status/" + payment.getId())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenThrow(new RuntimeException("status endpoint down"));

        strategy.reconcilePaymentWithBank(payment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Service Unavailable");
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Failed to reach bank service");
    }

    private void stubTxAndPaymentSave() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubBankStatusGet(String paymentId, BankPaymentResponse response) {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("http://mock-bank/status/" + paymentId)).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(BankPaymentResponse.class)).thenReturn(response);
    }

    private Payment samplePayment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .type(PaymentType.WITHDRAWAL)
                .idempotencyKey("withdraw-key")
                .amount(new BigDecimal("30.0000"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private PaymentRequest sampleRequest() {
        return new PaymentRequest(
                null,
                "withdraw-key",
                PaymentType.WITHDRAWAL,
                new BigDecimal("30.0000"),
                "USD"
        );
    }
}