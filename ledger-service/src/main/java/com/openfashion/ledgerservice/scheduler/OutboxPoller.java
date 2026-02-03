package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.model.OutboxEvent;
import com.openfashion.ledgerservice.dto.OutboxStatus;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

        if(events.isEmpty()) return;

        log.info("Polling outbox: found {} events to publish", events.size());

        for(OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload());

                outboxRepository.save(event);

                log.debug("Successfully published event {} to topic {}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}:{}", event.getId(), e.getMessage());
            }

            event.setStatus(OutboxStatus.PROCESSED);
        }
    }

}
