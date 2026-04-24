package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import io.confluent.parallelconsumer.RecordContext;

public interface DlqPublisher {

    void publishMalformedToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext);

    void publishUnsupportedTypeToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext,
                                     String eventType);

    void publishBusinessViolationMessageToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext);
}
