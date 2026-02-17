package org.example.paymentservice;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.*;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.strategy.WithdrawStrategy;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalStrategyTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private RestClient restClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    private WithdrawStrategy withdrawStrategy;
    private Payment payment;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        withdrawStrategy = new WithdrawStrategy(
                paymentRepository, outboxRepository, new ObjectMapper(),
                transactionTemplate, restClient, "http://ledger", "http://bank"
        );

        payment = Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency(CurrencyType.USD)
                .type(TransactionType.WITHDRAWAL)
                .idempotencyKey("test-key")
                .build();

        request = new PaymentRequest(null, "test-key", TransactionType.WITHDRAWAL, new BigDecimal("100.00"), "USD");

        lenient().doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("Strategy: Withdrawal Success (Reserve -> Bank -> Finalize)")
    void testWithdraw_FullSuccess() {
        // Prepare the bank response
        BankPaymentResponse bankResponse = new BankPaymentResponse(UUID.randomUUID(), BankPaymentStatus.APPROVED, "SUCCESS");

        // Single setup for all calls in this test
        setupRestClientMock(bankResponse);

        withdrawStrategy.execute(payment, request);

        verify(paymentRepository).save(argThat(p -> {
            if (p.getStatus() != PaymentStatus.AUTHORIZED) {
                System.out.println("DEBUG: Payment failed with message: " + p.getErrorMessage());
            }
            return p.getStatus() == PaymentStatus.AUTHORIZED;
        }));
        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.AUTHORIZED));
        verify(outboxRepository).save(argThat(o -> o.getEventType().equals("TRANSACTION_WITHDRAWAL_CONFIRMED")));
    }

    @Test
    @DisplayName("Strategy: Withdrawal Bank Decline (Reserve -> Bank Decline -> Release)")
    void testWithdraw_BankDeclined_TriggersRollback() {
        // Prepare a declined bank response
        BankPaymentResponse bankResponse = new BankPaymentResponse(null, BankPaymentStatus.DECLINED, "NSF");

        setupRestClientMock(bankResponse);

        withdrawStrategy.execute(payment, request);

        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.FAILED));
        verify(outboxRepository).save(argThat(o -> o.getEventType().equals("TRANSACTION_FAILED")));
    }

    private void setupRestClientMock(BankPaymentResponse bankResponse) {
        var uriSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
        var ledgerBodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);
        var bankBodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);

        var ledgerResponseSpec = mock(RestClient.ResponseSpec.class);
        var bankResponseSpec = mock(RestClient.ResponseSpec.class);

        lenient().when(restClient.post()).thenReturn(uriSpec);

        lenient().when(uriSpec.uri(contains("ledger"))).thenReturn(ledgerBodySpec);
        lenient().when(ledgerBodySpec.retrieve()).thenReturn(ledgerResponseSpec);
        lenient().when(ledgerResponseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        lenient().when(uriSpec.uri(contains("bank"))).thenReturn(bankBodySpec);
        lenient().when(bankBodySpec.retrieve()).thenReturn(bankResponseSpec);

        if (bankResponse != null) {
            lenient().doReturn(bankResponse).when(bankResponseSpec).body(any(Class.class));
        }
    }
}