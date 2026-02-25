package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.dto.event.WithdrawalEvent;
import com.openfashion.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class WithdrawalEventListener {

    private final LedgerService ledgerService;

    @KafkaListener(
            topics = {
                    "withdrawal.reserve",
                    "withdrawal.complete",
                    "withdrawal.release"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "withdrawalKafkaListenerContainerFactory"
    )
    public void listen(@Payload WithdrawalEvent event, Acknowledgment acknowledgment) {
        log.info("Received WithdrawalEvent: ref={}, status={}", event.referenceId(), event.status());

        try {
            ledgerService.handleWithdrawalEvent(event);

            acknowledgment.acknowledge();

            log.info("Successfully buffered and acknowledged withdrawal event: {}", event.referenceId());
        } catch (Exception e) {
            log.error("Failed to process withdrawal event {}: {}", event.referenceId(), e.getMessage());
        }
    }

}
