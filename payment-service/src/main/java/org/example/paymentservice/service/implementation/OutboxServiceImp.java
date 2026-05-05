package org.example.paymentservice.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.event.TransactionInitiatedEvent;
import org.example.paymentservice.dto.event.TransactionPayload;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.service.OutboxService;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxServiceImp implements OutboxService {

    private final ObjectMapper objectMapper;
    private final OutboxRepository outboxRepository;

    @Override
    public void saveOutboxEvent(Payment payment, TransactionStatus status, String userMessage) {
        // Create Payload
        TransactionPayload payloadData = new TransactionPayload(
                payment.getUserId(),
                payment.getReceiverId(),
                payment.getAmount(),
                payment.getCurrency().toString(),
                status,
                userMessage,
                payment.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                null
        );

        TransactionInitiatedEvent eventPayload = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                payment.getType(),
                payment.getId(),
                Instant.now(),
                payloadData
        );

        try {
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateId(payment.getUserId().toString())
                    .eventType(payment.getType())
                    .payload(jsonPayload)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to queue outbox event for payment {}", payment.getId(), e);
            throw new SerializationFailedException("Failed to serialize event", e);
        }
    }
}
