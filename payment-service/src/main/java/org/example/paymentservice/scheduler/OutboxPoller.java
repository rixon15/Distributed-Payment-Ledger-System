package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.OutboxStatus;
import org.example.paymentservice.repository.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 50)
    @Transactional
    public void processOutboxEvent() {
        List<OutboxEvent> events = outboxRepository.findTopForProcessing(500);

        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                String targetTopic = determineTopic(event.getEventType());

                kafkaTemplate.send(targetTopic, event.getAggregateId(), event.getPayload())
                        .get(3, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PROCESSED);
                outboxRepository.save(event);

                log.debug("Successfully published event {} to topic {}", event.getId(), event.getEventType());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                log.error("Thread was interrupted while sending event {}", event.getId());
            } catch (ExecutionException | TimeoutException e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getCause().getMessage());
            } catch (Exception e) {
                log.error("Unexpected error processing event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    private String determineTopic(String eventType) {
        return switch (eventType) {
            case "TRANSACTION_INITIATED" -> "transaction.initiated";
            case "WITHDRAWAL_RESERVED" -> "withdrawal.reserve";
            case "WITHDRAWAL_CONFIRMED" -> "withdrawal.complete";
            case "WITHDRAWAL_FAILED" -> "withdrawal.release";
            default -> "transaction.unknown";
        };
    }

}
