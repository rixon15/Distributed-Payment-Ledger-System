package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.service.DlqPublisher;
import io.confluent.parallelconsumer.RecordContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class DlqPublisherImp implements DlqPublisher {

    private static final String DLQ_TOPIC = "transaction.request.dlq";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void publishMalformedToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext) {

        try {
            String key = recordContext.key();

            String deserializerExceptionHeaderBase64 = null;

            Header exHeader = recordContext.headers().lastHeader("springDeserializerExceptionValue");
            if (exHeader != null && exHeader.value() != null) {
                deserializerExceptionHeaderBase64 = Base64.getEncoder().encodeToString(exHeader.value());
            }

            if (deserializerExceptionHeaderBase64 == null) {
                deserializerExceptionHeaderBase64 = "Failed to deserialize Exception header";
            }

            Map<String, Object> dlq = createDlqMap(
                    recordContext,
                    "DESERIALIZATION_ERROR",
                    "Unknown deserialization error",
                    Map.of(
                            "deserializationExceptionHeaderBase64", deserializerExceptionHeaderBase64
                    )
            );

            kafkaTemplate.send(DLQ_TOPIC, key, objectMapper.writeValueAsString(dlq));
            log.warn("Published malformed record to DLQ. key={}", key);

        } catch (Exception e) {
            log.error("Failed to publish malformed record to DLQ", e);
        }

    }

    @Override
    public void publishUnsupportedTypeToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext, String eventType) {

        try {

            Map<String, Object> dlq = createDlqMap(
                    recordContext,
                    "UNSUPPORTED_EVENT_TYPE",
                    "No strategy mapped for eventType=" + eventType
            );

            kafkaTemplate.send(DLQ_TOPIC, recordContext.key(), objectMapper.writeValueAsString(dlq));
        } catch (Exception e) {
            log.error("Failed to publish unsupported type to DLQ", e);
        }

    }

    @Override
    public void publishBusinessViolationMessageToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext) {

        try {

            Map<String, Object> dlq = createDlqMap(
                    recordContext,
                    "BUSINESS_VIOLATION",
                    "Business violation found in the message"
            );

            kafkaTemplate.send(DLQ_TOPIC, recordContext.key(), objectMapper.writeValueAsString(dlq));
        } catch (Exception e) {
            log.error("Failed to publish business violation to DLQ", e);
        }

    }

    private Map<String, Object> createDlqMap(RecordContext<String, TransactionInitiatedEvent> recordContext,
                                             String errorType,
                                             String errorMessage,
                                             Map<String, Object> parameters) {

        Map<String, Object> dlq = new HashMap<>();
        dlq.put("dlqId", UUID.randomUUID().toString());
        dlq.put("sourceTopic", recordContext.topic());
        dlq.put("sourcePartition", recordContext.partition());
        dlq.put("sourceOffset", recordContext.offset());
        dlq.put("key", recordContext.key());
        dlq.put("errorType", errorType);
        dlq.put("errorMessage", errorMessage);
        dlq.putAll(parameters);
        dlq.put("timestamp", Instant.now().toString());

        return dlq;
    }

    private Map<String, Object> createDlqMap(RecordContext<String, TransactionInitiatedEvent> recordContext,
                                             String errorType,
                                             String errorMessage) {

        Map<String, Object> dlq = new HashMap<>();
        dlq.put("dlqId", UUID.randomUUID().toString());
        dlq.put("sourceTopic", recordContext.topic());
        dlq.put("sourcePartition", recordContext.partition());
        dlq.put("sourceOffset", recordContext.offset());
        dlq.put("key", recordContext.key());
        dlq.put("errorType", errorType);
        dlq.put("errorMessage", errorMessage);
        dlq.put("timestamp", Instant.now().toString());

        return dlq;
    }
}
