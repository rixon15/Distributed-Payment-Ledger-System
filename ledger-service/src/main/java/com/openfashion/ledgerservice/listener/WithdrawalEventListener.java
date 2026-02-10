package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.dto.event.WithdrawalCompleteEvent;
import com.openfashion.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class WithdrawalEventListener {

    private final LedgerService ledgerService;

    @RetryableTopic(
            attempts = "5",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            kafkaTemplate = "kafkaTemplate"

    )
    @KafkaListener(
            topics = "transaction.withdrawal.confirmed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "withdrawalKafkaListenerContainerFactory"
    )
    public void handleWithdrawalComplete(WithdrawalCompleteEvent event, Acknowledgment ack) {
        log.info("Received withdrawal completed event: {}", event.referenceId());

        try {
            ledgerService.processWithdrawal(event);

            ack.acknowledge();
        } catch (Exception _) {
            log.error("Error processing withdrawal event: {}", event.referenceId());
        }

    }


}
