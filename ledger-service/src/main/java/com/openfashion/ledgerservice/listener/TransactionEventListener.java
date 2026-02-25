package com.openfashion.ledgerservice.listener;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventListener {

    private final LedgerService ledgerService;

    @RetryableTopic(
            attempts = "5",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            kafkaTemplate = "kafkaTemplate"

    )
    @KafkaListener(
            topics = "transaction.initiated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "initiatedKafkaListenerContainerFactory"
    )
    public void handleTransactionInitiated(TransactionInitiatedEvent event, Acknowledgment acknowledgment) {
        log.info("Received transaction event: {} type: {}", event.eventId(), event.payload().type());

        try {
            TransactionRequest request = mapEventToRequest(event);

            ledgerService.processTransaction(request);

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Error processing transaction event: {}", event.referenceId(), e);
            //TODO: Create wrapper around the event to keep count of retry and if retry > 3 move to DLQ
        }
    }

    private TransactionRequest mapEventToRequest(TransactionInitiatedEvent event) {
        TransactionRequest request = new TransactionRequest();
        TransactionPayload payload = event.payload(); // Access the nested payload

        request.setReferenceId(event.referenceId()); // This was event.aggregatedId
        request.setType(TransactionType.valueOf(payload.type()));
        request.setCurrency(CurrencyType.valueOf(payload.currency()));
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());
        request.setAmount(payload.amount());
        request.setMetadata(payload.userMessage());

        return request;
    }

}
