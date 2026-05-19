package org.example.paymentservice.unit.strategies;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.OutboxService;
import org.example.paymentservice.service.strategy.InternalTransferStrategy;
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
class InternalTransferStrategyUnitTest {

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

    private InternalTransferStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new InternalTransferStrategy(paymentRepository, objectMapper, tx, restClient, outboxService);
    }

    @Test
    void supports_shouldReturnTrueForTransferPaymentRefundOnly() {
        assertThat(strategy.supports(PaymentType.TRANSFER)).isTrue();
        assertThat(strategy.supports(PaymentType.PAYMENT)).isTrue();
        assertThat(strategy.supports(PaymentType.REFUND)).isTrue();

        assertThat(strategy.supports(PaymentType.DEPOSIT)).isFalse();
        assertThat(strategy.supports(PaymentType.WITHDRAWAL)).isFalse();
    }

    @Test
    void execute_shouldFinalizeAuthorizedAndEmitPostedOutbox() {
        stubTxAndPaymentSave();

        Payment payment = samplePayment(PaymentType.TRANSFER);
        PaymentRequest request = sampleRequest(PaymentType.TRANSFER);

        strategy.execute(payment, request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getExternalTransactionId()).isNull();

        verify(paymentRepository).save(payment);
        verify(outboxService).saveOutboxEvent(payment, TransactionStatus.POSTED, null);
        verifyNoInteractions(restClient);
    }

    private void stubTxAndPaymentSave() {
        doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Payment samplePayment(PaymentType type) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .type(type)
                .idempotencyKey("internal-key")
                .amount(new BigDecimal("10.0000"))
                .currency(CurrencyType.USD)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private PaymentRequest sampleRequest(PaymentType type) {
        return new PaymentRequest(
                UUID.randomUUID(),
                "internal-key",
                type,
                new BigDecimal("10.0000"),
                "USD"
        );
    }
}