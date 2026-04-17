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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

            String errorType = "DESERIALIZATION_ERROR";
            String errorMessage = "Unknown deserialization error";
            String deserializerExceptionHeaderBase64 = null;

            Header exHeader = recordContext.headers().lastHeader("springDeserializerExceptionValue");
            if (exHeader != null && exHeader.value() != null) {
                deserializerExceptionHeaderBase64 = Base64.getEncoder().encodeToString(exHeader.value());
            }


            Map<String, Object> dlq = new HashMap<>();
            dlq.put("dlqId", UUID.randomUUID().toString());
            dlq.put("sourceTopic", recordContext.topic());
            dlq.put("sourcePartition", recordContext.partition());
            dlq.put("sourceOffset", recordContext.offset());
            dlq.put("key", recordContext.key());
            dlq.put("errorType", errorType);
            dlq.put("errorMessage", errorMessage);
            dlq.put("deserializerExceptionHeaderBase64", deserializerExceptionHeaderBase64);
            dlq.put("timestamp", Instant.now().toString());

            kafkaTemplate.send(DLQ_TOPIC, key, objectMapper.writeValueAsString(dlq));
            log.warn("Published malformed record to DLQ. key={}", key);

        } catch (Exception e) {
            log.error("Failed to publish malformed record to DLQ", e);
        }

    }

    @Override
    public void publishUnsupportedTypeToDlq(RecordContext<String, TransactionInitiatedEvent> recordContext, String eventType) {

        try {
            Map<String, Object> dlq = new HashMap<>();
            dlq.put("dlqId", UUID.randomUUID().toString());
            dlq.put("sourceTopic", recordContext.topic());
            dlq.put("sourcePartition", recordContext.partition());
            dlq.put("sourceOffset", recordContext.offset());
            dlq.put("key", recordContext.key());
            dlq.put("errorType", "UNSUPPORTED_EVENT_TYPE");
            dlq.put("errorMessage", "No strategy mapped for eventType=" + eventType);
            dlq.put("timestamp", Instant.now().toString());

            kafkaTemplate.send(DLQ_TOPIC, recordContext.key(), objectMapper.writeValueAsString(dlq));
        } catch (Exception e) {
            log.error("Failed to publish unsupported type to DLQ", e);
        }

    }
}
