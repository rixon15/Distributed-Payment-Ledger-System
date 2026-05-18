package org.example.paymentservice.unit.services;

import org.example.paymentservice.dto.event.TransactionInitiatedEvent;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.service.implementation.OutboxServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.serializer.support.SerializationFailedException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OutboxServiceUnitTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxServiceImp outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxServiceImp(objectMapper, outboxRepository);
    }

    @Test
    void saveOutboxEvent_shouldSerializeAndPersistOutboxRecord() {
        Payment payment = samplePayment();
        String userMessage = "Payment authorized";
        String jsonPayload = "{\"event\":\"ok\"}";

        when(objectMapper.writeValueAsString(any(TransactionInitiatedEvent.class))).thenReturn(jsonPayload);
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        outboxService.saveOutboxEvent(payment, TransactionStatus.POSTED, userMessage);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        verify(objectMapper).writeValueAsString(any(TransactionInitiatedEvent.class));

        OutboxEvent saved = outboxCaptor.getValue();
        assertThat(saved.getAggregateId()).isEqualTo(payment.getUserId().toString());
        assertThat(saved.getEventType()).isEqualTo(payment.getType());
        assertThat(saved.getPayload()).isEqualTo(jsonPayload);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void saveOutboxEvent_shouldThrowSerializationFailedException_whenSerializationFails() {
        Payment payment = samplePayment();

        when(objectMapper.writeValueAsString(any(TransactionInitiatedEvent.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> outboxService.saveOutboxEvent(payment, TransactionStatus.FAILED, "reason"))
                .isInstanceOf(SerializationFailedException.class)
                .hasMessageContaining("Failed to serialize event")
                .hasCauseInstanceOf(RuntimeException.class);

        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    private Payment samplePayment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .type(PaymentType.TRANSFER)
                .idempotencyKey("outbox-key")
                .amount(new BigDecimal("12.3400"))
                .currency(CurrencyType.USD)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
