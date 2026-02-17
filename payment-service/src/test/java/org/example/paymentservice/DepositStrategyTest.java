package org.example.paymentservice;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.strategy.DepositStrategy;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositStrategyTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private RestClient restClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    private DepositStrategy depositStrategy;
    private Payment payment;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        depositStrategy = new DepositStrategy(
                paymentRepository, outboxRepository, new ObjectMapper(),
                transactionTemplate, restClient, "http://bank"
        );

        payment = Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(new BigDecimal("250.00"))
                .currency(CurrencyType.USD)
                .type(TransactionType.DEPOSIT)
                .idempotencyKey("deposit-key")
                .build();

        request = new PaymentRequest(null, "deposit-key", TransactionType.DEPOSIT, new BigDecimal("250.00"), "USD");

        // Transaction mock using the correct Consumer casting
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("Strategy: Deposit Approved -> Finalize AUTHORIZED and Notify Ledger")
    void testDeposit_Success() {
        BankPaymentResponse bankPaymentResponse = new BankPaymentResponse(
                UUID.randomUUID(),
                BankPaymentStatus.APPROVED,
                "SUCCESS"
        );
        setupBankMock(bankPaymentResponse);

        depositStrategy.execute(payment, request);

        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.AUTHORIZED));
        verify(outboxRepository).save(argThat(o -> o.getEventType().equals("TRANSACTION_INITIATED")));
    }

    @Test
    @DisplayName("Strategy: Deposit Declined -> Finalize FAILED")
    void testDeposit_Declined() {
        BankPaymentResponse bankPaymentResponse = new BankPaymentResponse(
                null,
                BankPaymentStatus.DECLINED,
                "INSUFFICIENT_FUNDS"
        );
        setupBankMock(bankPaymentResponse);

        depositStrategy.execute(payment, request);

        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.FAILED));
        verify(outboxRepository).save(argThat(o -> o.getEventType().equals("TRANSACTION_FAILED")));
    }

    private void setupBankMock(BankPaymentResponse bankResponse) {
        var uriSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
        var bodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        lenient().when(restClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        lenient().when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        lenient().when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (bankResponse != null) {
            lenient().doReturn(bankResponse).when(responseSpec).body(any(Class.class));
        }
    }

}
