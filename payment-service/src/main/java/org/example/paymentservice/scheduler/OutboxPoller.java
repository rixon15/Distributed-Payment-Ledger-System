package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.OutboxStatus;
import org.example.paymentservice.repository.OutboxRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void processOutboxEvent() {
        List<OutboxEvent> events = outboxRepository.findTop50ForProcessing();

        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                String targetTopic = determineTopic(event.getEventType());

                kafkaTemplate.send(targetTopic, event.getAggregateId(), event.getPayload());

                event.setStatus(OutboxStatus.PROCESSED);
                outboxRepository.save(event);

                log.debug("Published event {} to topic {}", event.getId(), targetTopic);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    private String determineTopic(String eventType) {
        return switch (eventType) {
            case "TRANSACTION_INITIATED" -> "transaction.initiated";
            case "TRANSACTION_WITHDRAWAL_CONFIRMED" -> "transaction.withdrawal.confirmed";
            case "TRANSACTION_FAILED" -> "transaction.failed";
            case null, default -> "transaction.unknown";
        };
    }

}
