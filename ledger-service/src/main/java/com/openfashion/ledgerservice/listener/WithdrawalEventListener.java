package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.dto.event.WithdrawalConfirmedEvent;
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
    public void handleWithdrawalConfirmed(WithdrawalConfirmedEvent event, Acknowledgment ack) {
        log.info("Received withdrawal confirmed event: {}", event.referenceId());

        try {
            ledgerService.processWithdrawal(event);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing withdrawal event: {}", event.referenceId(), e);
        }

    }


}
