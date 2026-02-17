package org.example.paymentservice;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.strategy.InternalTransferStrategy;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalTransferStrategyTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private RestClient restClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    private InternalTransferStrategy transferStrategy;
    private Payment payment;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        transferStrategy = new InternalTransferStrategy(
                paymentRepository, outboxRepository, new ObjectMapper(),
                transactionTemplate, restClient
        );

        payment = Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .amount(new BigDecimal("75.00"))
                .currency(CurrencyType.USD)
                .type(TransactionType.TRANSFER)
                .idempotencyKey("internal-key-123")
                .build();

        request = new PaymentRequest(
                payment.getReceiverId(),
                "internal-key-123",
                TransactionType.TRANSFER,
                new BigDecimal("75.00"),
                "USD"
        );

        // Standard transaction mock
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("Strategy: Internal Transfer should move to AUTHORIZED and create outbox event")
    void testInternalTransfer_Success() {
        transferStrategy.execute(payment, request);

        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.AUTHORIZED));
        verify(outboxRepository).save(argThat(o ->
                o.getEventType().equals("TRANSACTION_INITIATED") &&
                        o.getAggregateId().equals(payment.getId().toString())));
    }

    @Test
    @DisplayName("Strategy: should support TRANSFER, PAYMENT, and REFUND types")
    void testSupports_InternalTypes() {
        assertThat(transferStrategy.supports(TransactionType.TRANSFER)).isTrue();
        assertThat(transferStrategy.supports(TransactionType.PAYMENT)).isTrue();
        assertThat(transferStrategy.supports(TransactionType.REFUND)).isTrue();
    }
}
