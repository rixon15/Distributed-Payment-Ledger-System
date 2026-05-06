package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import io.confluent.parallelconsumer.RecordContext;

/**
 * Publisher for routing invalid/failed transaction records to a Dead Letter Queue (DLQ).
 * <p>The DLQ provides an audit trail for messages that could not be processed
 * through the main ledger ingestion pipeline, enabling operators to investigate
 * and remediate data quality issues.
 */
public interface DlqPublisher {

    /**
     * Publishes a record with deserialization/parsing errors to the DLQ.
     * <p>Invoked when the Kafka consumer fails to deserialize an inbound message
     * into a {@code TransactionInitiatedEvent}, typically due to invalid JSON or
     * schema mismatch.
     * @param recordContext the raw Kafka record context including headers, offset, and error metadata
     */
    void publishMalformedToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext);

    /**
     * Publishes a record with an unsupported or unmapped transaction type to the DLQ.
     * <p>Invoked when the event's {@code eventType} does not match any registered
     * strategy in {@link com.openfashion.ledgerservice.listener.TransactionEventListener}.
     * @param recordContext the Kafka record context for tracking provenance
     * @param eventType the unsupported transaction type string from the event
     */
    void publishUnsupportedTypeToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext,
                                     String eventType);

    /**
     * Publishes a record that violates business validation rules to the DLQ.
     * <p>Invoked when the event payload fails strategy-specific validation checks
     * (e.g., self-transfer, zero/negative amount, account status constraints, currency mismatch).
     * These records are also persisted as {@code REJECTED_VALIDATION} transactions in Postgres
     * for audit purposes.
     * @param recordContext the Kafka record context for tracking and debugging
     */
    void publishBusinessViolationMessageToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext);
}
