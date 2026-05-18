package org.example.paymentservice.unit.strategies;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.OutboxService;
import org.example.paymentservice.service.strategy.DepositStrategy;
import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
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
class DepositStrategyUnitTest {

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

    private DepositStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DepositStrategy(
                paymentRepository,
                objectMapper,
                tx,
                restClient,
                "http://mock-bank",
                outboxService
        );
    }

    @Test
    void supports_shouldReturnTrueOnlyForDeposit() {
        assertThat(strategy.supports(PaymentType.DEPOSIT)).isTrue();
        assertThat(strategy.supports(PaymentType.TRANSFER)).isFalse();
        assertThat(strategy.supports(PaymentType.WITHDRAWAL)).isFalse();
        assertThat(strategy.supports(PaymentType.PAYMENT)).isFalse();
        assertThat(strategy.supports(PaymentType.REFUND)).isFalse();
    }

    @Test
    void execute_shouldFinalizeAuthorized_whenBankApproves() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        PaymentRequest request = sampleDepositRequest("25.0000", "USD");
        UUID bankTxId = UUID.randomUUID();
        BankPaymentResponse response = new BankPaymentResponse(bankTxId, BankPaymentStatus.APPROVED, "OK");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://mock-bank/pay")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(BankPaymentRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(BankPaymentResponse.class)).thenReturn(response);

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isEqualTo(bankTxId.toString());
        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
    }

    @Test
    void execute_shouldFail_whenBankDeclines() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        PaymentRequest request = sampleDepositRequest("25.0000", "USD");
        BankPaymentResponse response =
                new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.DECLINED, "INSUFFICIENT_FUNDS");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://mock-bank/pay")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(BankPaymentRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(BankPaymentResponse.class)).thenReturn(response);

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank Declined: INSUFFICIENT_FUNDS");
        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "INSUFFICIENT_FUNDS");
    }

    @Test
    void execute_shouldFail_whenBankResponseIsNull() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        PaymentRequest request = sampleDepositRequest("25.0000", "USD");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://mock-bank/pay")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(BankPaymentRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(BankPaymentResponse.class)).thenReturn(null);

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).isEqualTo("Bank API returned null response");
        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Failed to reach bank service");
    }

    @Test
    void execute_shouldFail_whenBankApiThrowsException() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment();
        PaymentRequest request = sampleDepositRequest("25.0000", "USD");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://mock-bank/pay")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(BankPaymentRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RuntimeException("bank down"));

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getErrorMessage()).startsWith("Bank Service Unavailable:");
        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.FAILED, "Failed to reach bank service");
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
                .type(PaymentType.DEPOSIT)
                .idempotencyKey("deposit-key")
                .amount(new BigDecimal("25.0000"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private PaymentRequest sampleDepositRequest(String amount, String currency) {
        return new PaymentRequest(
                null,
                "deposit-key",
                PaymentType.DEPOSIT,
                new BigDecimal(amount),
                currency
        );
    }
}