package com.openfashion.ledgerservice.unit.services;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.service.imp.DlqPublisherImp;
import io.confluent.parallelconsumer.RecordContext;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked"})
class DlqPublisherUnitTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private RecordContext<String, TransactionInitiatedEvent> recordContext;
    private Headers headers;

    private DlqPublisherImp service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        recordContext = mock(RecordContext.class);
        headers = mock(Headers.class);

        service = new DlqPublisherImp(kafkaTemplate);

        lenient().when(recordContext.headers()).thenReturn(headers);
        lenient().when(recordContext.topic()).thenReturn("transaction.request");
        lenient().when(recordContext.partition()).thenReturn(2);
        lenient().when(recordContext.offset()).thenReturn(123L);
        lenient().when(recordContext.key()).thenReturn("payment-key-1");
    }

    @Test
    void publishMalformedToDlq_shouldSendSerializedDlqPayloadWithEncodedDeserializerHeader() {
        byte[] rawHeaderValue = "boom".getBytes(StandardCharsets.UTF_8);
        Header header = mock(Header.class);

        when(headers.lastHeader("springDeserializerExceptionValue")).thenReturn(header);
        when(header.value()).thenReturn(rawHeaderValue);

        service.publishMalformedToDlq(recordContext);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("transaction.request.dlq"),
                eq("payment-key-1"),
                payloadCaptor.capture()
        );

        String payload = payloadCaptor.getValue();
        Map<String, Object> dlq = objectMapper.readValue(payload, Map.class);

        assertThat(dlq).containsEntry("sourceTopic", "transaction.request")
                .containsEntry("sourcePartition", 2)
                .containsEntry("sourceOffset", 123)
                .containsEntry("key", "payment-key-1")
                .containsEntry("errorType", "DESERIALIZATION_ERROR")
                .containsEntry("errorMessage", "Unknown deserialization error")
                .containsEntry("deserializationExceptionHeaderBase64", Base64.getEncoder().encodeToString(rawHeaderValue));
        assertThat(dlq.get("dlqId")).isNotNull();
        assertThat(dlq.get("timestamp")).isNotNull();
    }

    @Test
    void publishMalformedToDlq_shouldUseFallbackMessageWhenDeserializerHeaderMissing() {
        when(headers.lastHeader("springDeserializerExceptionValue")).thenReturn(null);

        service.publishMalformedToDlq(recordContext);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("transaction.request.dlq"),
                eq("payment-key-1"),
                payloadCaptor.capture()
        );

        Map<String, Object> dlq = objectMapper.readValue(payloadCaptor.getValue(), Map.class);

        assertThat(dlq).containsEntry("errorType", "DESERIALIZATION_ERROR")
                .containsEntry("deserializationExceptionHeaderBase64", "Failed to deserialize Exception header");
    }

    @Test
    void publishMalformedToDlq_shouldUseFallbackMessageWhenHeaderValueIsNull() {
        Header header = mock(Header.class);

        when(headers.lastHeader("springDeserializerExceptionValue")).thenReturn(header);
        when(header.value()).thenReturn(null);

        service.publishMalformedToDlq(recordContext);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("transaction.request.dlq"),
                eq("payment-key-1"),
                payloadCaptor.capture()
        );

        Map<String, Object> dlq = objectMapper.readValue(payloadCaptor.getValue(), Map.class);

        assertThat(dlq).containsEntry("deserializationExceptionHeaderBase64",
                "Failed to deserialize Exception header");
    }

    @Test
    void publishUnsupportedTypeToDlq_shouldSendSerializedDlqPayload() {
        service.publishUnsupportedTypeToDlq(recordContext, "SOMETHING_NEW");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("transaction.request.dlq"),
                eq("payment-key-1"),
                payloadCaptor.capture()
        );

        Map<String, Object> dlq = objectMapper.readValue(payloadCaptor.getValue(), Map.class);

        assertThat(dlq).containsEntry("sourceTopic", "transaction.request")
                .containsEntry("sourcePartition", 2)
                .containsEntry("sourceOffset", 123)
                .containsEntry("key", "payment-key-1")
                .containsEntry("errorType", "UNSUPPORTED_EVENT_TYPE")
                .containsEntry("errorMessage", "No strategy mapped for eventType=SOMETHING_NEW");

        assertThat(dlq.get("dlqId")).isNotNull();
        assertThat(dlq.get("timestamp")).isNotNull();
    }

    @Test
    void publishBusinessViolationMessageToDlq_shouldSendSerializedDlqPayload() {
        service.publishBusinessViolationMessageToDlq(recordContext);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("transaction.request.dlq"),
                eq("payment-key-1"),
                payloadCaptor.capture()
        );

        Map<String, Object> dlq = objectMapper.readValue(payloadCaptor.getValue(), Map.class);

        assertThat(dlq).containsEntry("sourceTopic", "transaction.request")
                .containsEntry("sourcePartition", 2)
                .containsEntry("sourceOffset", 123)
                .containsEntry("key", "payment-key-1")
                .containsEntry("errorType", "BUSINESS_VIOLATION")
                .containsEntry("errorMessage", "Business violation found in the message");

        assertThat(dlq.get("dlqId")).isNotNull();
        assertThat(dlq.get("timestamp")).isNotNull();
    }

    @Test
    void publishMalformedToDlq_shouldSwallowKafkaSendExceptions() {
        when(headers.lastHeader("springDeserializerExceptionValue")).thenReturn(null);
        doThrow(new RuntimeException("kafka down"))
                .when(kafkaTemplate)
                .send(eq("transaction.request.dlq"), eq("payment-key-1"), anyString());

        assertThatCode(() -> service.publishMalformedToDlq(recordContext))
                .doesNotThrowAnyException();
    }

    @Test
    void publishUnsupportedTypeToDlq_shouldSwallowKafkaSendExceptions() {
        doThrow(new RuntimeException("kafka down"))
                .when(kafkaTemplate)
                .send(eq("transaction.request.dlq"), eq("payment-key-1"), anyString());

        assertThatCode(() -> service.publishUnsupportedTypeToDlq(recordContext, "UNKNOWN"))
                .doesNotThrowAnyException();
    }

    @Test
    void publishBusinessViolationMessageToDlq_shouldSwallowKafkaSendExceptions() {
        doThrow(new RuntimeException("kafka down"))
                .when(kafkaTemplate)
                .send(eq("transaction.request.dlq"), eq("payment-key-1"), anyString());

        assertThatCode(() -> service.publishBusinessViolationMessageToDlq(recordContext))
                .doesNotThrowAnyException();
    }
}