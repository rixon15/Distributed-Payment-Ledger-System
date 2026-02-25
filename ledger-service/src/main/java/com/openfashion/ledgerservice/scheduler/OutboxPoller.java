package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.model.OutboxEvent;
import com.openfashion.ledgerservice.model.OutboxStatus;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TRANSACTION_POSTED_NAME = "transaction.posted";
    private static final int DELAY = 2000;
    private static final int LIMIT = 100;

    @Scheduled(fixedDelay = DELAY)
    @Transactional
    public void processOutboxEvent() {
        List<OutboxEvent> events = outboxRepository.findTopForProcessing(LIMIT);

        if (events.isEmpty()) return;

        log.info("Polling outbox: found {} events to publish", events.size());

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
            case "TRANSACTION_COMPLETED", "WITHDRAWAL_SETTLED" -> TRANSACTION_POSTED_NAME;
            case "WITHDRAWAL_RESERVED" -> "withdrawal.reserve";
            case "TRANSACTION_FAILED" -> "transaction.failed";
            case "FUNDS_RESERVED_SUCCESS" -> "ledger.funds.reserved";
            case null, default -> "transaction.unknown";
        };
    }
}
