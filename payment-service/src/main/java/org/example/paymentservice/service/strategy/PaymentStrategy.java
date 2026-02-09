package org.example.paymentservice.service.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.TransactionInitiatedEvent;
import org.example.paymentservice.dto.event.TransactionPayload;
import org.example.paymentservice.model.*;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public abstract class PaymentStrategy {

    protected final PaymentRepository paymentRepository;
    protected final OutboxRepository outboxRepository;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate tx;
    protected final RestClient restClient;


    public abstract boolean supports(TransactionType type);

    public abstract void execute(Payment payment, PaymentRequest request);

    protected void saveOutboxEvent(Payment payment, String eventType, String userMessage) {
        // Create Payload
        TransactionPayload payloadData = new TransactionPayload(
                payment.getType(),
                payment.getUserId(),
                payment.getReceiverId(),
                payment.getAmount(),
                payment.getCurrency().toString(),
                userMessage,
                null
        );

        TransactionInitiatedEvent eventPayload = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                eventType,
                payment.getId().toString(),
                Instant.now(),
                payloadData
        );

        try {
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateId(payment.getId().toString())
                    .eventType(eventType)
                    .payload(jsonPayload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outbox);
        } catch (Exception e) {
            throw new SerializationFailedException("Failed to serialize event", e);
        }
    }

    protected void handleFailure(Payment payment, String internalReason, String userMessage) {
        log.warn("Payment {} failed: {}", payment.getId(), internalReason);

        tx.executeWithoutResult(status -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage(internalReason);
            paymentRepository.save(payment);

            saveOutboxEvent(payment, "TRANSACTION_FAILED", userMessage);
        });
    }

    protected void finalizeStatus(Payment payment, PaymentStatus status, UUID externalId) {
        tx.executeWithoutResult(ts -> {
            payment.setStatus(status);
            payment.setExternalTransactionId(externalId != null ? externalId.toString() : null);
            paymentRepository.save(payment);

            String eventType = (payment.getType() == TransactionType.WITHDRAWAL)
                    ? "TRANSACTION_WITHDRAWAL_CONFIRMED" : "TRANSACTION_INITIATED";

            saveOutboxEvent(payment, eventType, null);
        });
    }
}
