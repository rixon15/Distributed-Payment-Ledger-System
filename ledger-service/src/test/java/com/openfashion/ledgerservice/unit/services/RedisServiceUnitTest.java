package com.openfashion.ledgerservice.unit.services;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.consumer.BatchToken;
import com.openfashion.ledgerservice.dto.redis.AckResult;
import com.openfashion.ledgerservice.dto.redis.StreamEnvelope;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.service.imp.RedisServiceImp;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class RedisServiceUnitTest {

    private RedisTemplate<String, String> balanceTemplate;
    private HashOperations hashOperations;
    private StreamOperations streamOperations;
    private RedisOperations redisOperations;

    private RedisServiceImp service;

    @BeforeEach
    void setUp() {
        balanceTemplate = mock(RedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        streamOperations = mock(StreamOperations.class);
        redisOperations = mock(RedisOperations.class);

        lenient().when(balanceTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(balanceTemplate.opsForStream()).thenReturn(streamOperations);

        service = new RedisServiceImp(balanceTemplate);

        ReflectionTestUtils.setField(service, "initialized", true);
        ReflectionTestUtils.setField(service, "consumerName", "test-consumer");
    }

    @Test
    void processBatchAtomic_shouldSplitOkAndNsf_andIgnoreDuplicates() {
        TransactionRequest okRequest = request(TransactionType.TRANSFER, "10.0000");
        TransactionRequest nsfRequest = request(TransactionType.TRANSFER, "20.0000");
        TransactionRequest duplicateRequest = request(TransactionType.TRANSFER, "30.0000");

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK", "NSF", "DUPLICATE");
                });

        Map<String, List<TransactionRequest>> result = service.processBatchAtomic(
                List.of(okRequest, nsfRequest, duplicateRequest),
                "batch-1"
        );

        assertThat(result.get("ok")).containsExactly(okRequest);
        assertThat(result.get("nsf")).containsExactly(nsfRequest);
    }

    @Test
    void processBatchAtomic_shouldThrowOnUnexpectedRedisResponse() {
        TransactionRequest request = request(TransactionType.TRANSFER, "10.0000");

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("WAT");
                });

        assertThatThrownBy(() -> service.processBatchAtomic(List.of(request), "batch-1"))
                .isInstanceOf(RedisException.class)
                .hasMessageContaining("Unexpected Redis response: WAT");
    }

    @Test
    void processBatchAtomic_shouldDisableNsfForDepositSettleAndRelease() {
        TransactionRequest deposit = request(TransactionType.DEPOSIT, "10.0000");
        TransactionRequest settle = request(TransactionType.WITHDRAWAL_SETTLE, "10.0000");
        TransactionRequest release = request(TransactionType.WITHDRAWAL_RELEASE, "10.0000");

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK", "OK", "OK");
                });

        service.processBatchAtomic(List.of(deposit, settle, release), "batch-no-nsf");

        verify(redisOperations, times(3)).execute(
                any(),
                any(List.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("0"),
                any()
        );
    }

    @Test
    void processBatchAtomic_shouldEnableNsfForTransfer() {
        TransactionRequest transfer = request(TransactionType.TRANSFER, "10.0000");

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK");
                });

        service.processBatchAtomic(List.of(transfer), "batch-transfer");

        verify(redisOperations).execute(
                any(),
                any(List.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("1"),
                eq("batch-transfer")
        );
    }

    @Test
    void initializeSnapshotIfMissing_shouldPutIfAbsent() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setBalance(new BigDecimal("123.4500"));

        service.initializeSnapshotIfMissing(account);

        verify(hashOperations).putIfAbsent(
                "ledger:db:snapshot",
                account.getId().toString(),
                "123.4500"
        );
    }

    @Test
    void acknowledgePersisted_shouldShortCircuitForEmptyBatch() {
        AckResult result = service.acknowledgePersisted(List.of());

        assertThat(result.requested()).isZero();
        assertThat(result.acked()).isZero();
        assertThat(result.missingIds()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();

        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    void acknowledgePersisted_shouldReturnSuccessWhenAllAcked() {
        StreamEnvelope<TransactionRequest> env1 = envelope("1-0");
        StreamEnvelope<TransactionRequest> env2 = envelope("2-0");

        when(streamOperations.acknowledge(eq("ledger:stream:tx"), eq("ledger-stream-group"), any(RecordId[].class)))
                .thenReturn(2L);

        AckResult result = service.acknowledgePersisted(List.of(env1, env2));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.acked()).isEqualTo(2);
        assertThat(result.missingIds()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void acknowledgePersisted_shouldReturnPartialFailureWhenAckCountDiffers() {
        StreamEnvelope<TransactionRequest> env1 = envelope("1-0");
        StreamEnvelope<TransactionRequest> env2 = envelope("2-0");

        when(streamOperations.acknowledge(eq("ledger:stream:tx"), eq("ledger-stream-group"), any(RecordId[].class)))
                .thenReturn(1L);

        AckResult result = service.acknowledgePersisted(List.of(env1, env2));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.acked()).isEqualTo(1);
        assertThat(result.missingIds()).containsExactly("1-0", "2-0");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Partial XACK");
    }

    @Test
    void acknowledgePersisted_shouldReturnFailureWhenAcknowledgeThrows() {
        StreamEnvelope<TransactionRequest> env1 = envelope("1-0");

        when(streamOperations.acknowledge(eq("ledger:stream:tx"), eq("ledger-stream-group"), any(RecordId[].class)))
                .thenThrow(new RuntimeException("ack failed"));

        AckResult result = service.acknowledgePersisted(List.of(env1));

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.acked()).isZero();
        assertThat(result.missingIds()).containsExactly("1-0");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ack failed");
    }

    @Test
    void moveToDlqAndAck_shouldAddToDlqAndAcknowledge() {
        StreamEnvelope<TransactionRequest> failed = new StreamEnvelope<>(
                "42-0",
                "batch-42",
                "{\"referenceId\":\"abc\"}",
                null,
                2
        );

        service.moveToDlqAndAck(failed, "PARSE_ERROR");

        verify(streamOperations).add(eq("ledger:stream:tx:dlq"), argThat(map ->
                "42-0".equals(map.get("streamId")) &&
                        "{\"referenceId\":\"abc\"}".equals(map.get("payload")) &&
                        "PARSE_ERROR".equals(map.get("reason")) &&
                        map.containsKey("timestamp")
        ));

        verify(streamOperations).acknowledge(
                "ledger:stream:tx",
                "ledger-stream-group",
                RecordId.of("42-0")
        );
    }

    @Test
    void moveToDlqAndAck_shouldUseLiteralNullPayloadWhenRawJsonMissing() {
        StreamEnvelope<TransactionRequest> failed = new StreamEnvelope<>(
                "43-0",
                "batch-43",
                null,
                null,
                1
        );

        service.moveToDlqAndAck(failed, "MISSING_PAYLOAD");

        verify(streamOperations).add(eq("ledger:stream:tx:dlq"), argThat(map ->
                "null".equals(map.get("payload"))
        ));
    }

    @Test
    void moveToDlqAndAck_shouldSwallowExceptions() {
        StreamEnvelope<TransactionRequest> failed = envelope("44-0");

        doThrow(new RuntimeException("dlq failed"))
                .when(streamOperations).add(anyString(), anyMap());

        service.moveToDlqAndAck(failed, "ANY");

        verify(streamOperations, never()).acknowledge(eq("ledger:stream:tx"), eq("ledger-stream-group"), any(RecordId.class));
    }

    @Test
    void markBatchProgress_shouldNoOpWhenAckedCountIsZeroOrLess() {
        service.markBatchProgress("batch-1", 0);
        service.markBatchProgress("batch-1", -5);

        verify(balanceTemplate, never()).execute(any(), any(List.class), any(), any());
        verify(balanceTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void markBatchProgress_shouldExecuteScriptAndExpire() {
        when(balanceTemplate.execute(any(), any(List.class), any(), any())).thenReturn(1L);

        service.markBatchProgress("batch-99", 3);

        verify(balanceTemplate).execute(
                any(),
                eq(List.of("ledger:batch:meta:batch-99", "ledger:stream:batch:done")),
                eq("batch-99"),
                eq("3")
        );
        verify(balanceTemplate).expire("ledger:batch:meta:batch-99", Duration.ofMinutes(10));
    }

    @Test
    void awaitBatchCompletion_shouldReturnTrueWhenAlreadyDone() {
        when(hashOperations.get("ledger:batch:meta:batch-done", "status")).thenReturn("DONE");

        boolean result = service.awaitBatchCompletion("batch-done", Duration.ofMillis(100));

        assertThat(result).isTrue();
        verify(streamOperations, never()).read(any(StreamReadOptions.class), any(StreamOffset.class));
    }

    @Test
    void awaitBatchCompletion_shouldReturnTrueWhenDoneEventArrives() {
        when(hashOperations.get("ledger:batch:meta:batch-7", "status"))
                .thenReturn("PENDING", "PENDING");

        MapRecord<String, Object, Object> doneEvent = StreamRecords.newRecord()
                .in("ledger:stream:batch:done")
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of(
                        "batchId", "batch-7",
                        "status", "DONE"
                ));

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(doneEvent));

        boolean result = service.awaitBatchCompletion("batch-7", Duration.ofMillis(250));

        assertThat(result).isTrue();
    }

    @Test
    void awaitBatchCompletion_shouldReturnFalseOnTimeout() {
        when(hashOperations.get("ledger:batch:meta:batch-timeout", "status"))
                .thenReturn("PENDING");

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        boolean result = service.awaitBatchCompletion("batch-timeout", Duration.ofMillis(25));

        assertThat(result).isFalse();
    }

    @Test
    void createBatchToken_shouldInitializeHashAndExpire() {
        BatchToken token = service.createBatchToken();

        assertThat(token.batchId()).isNotBlank();
        assertThat(token.expectedCount()).isZero();

        verify(hashOperations).putAll(
                "ledger:batch:meta:" + token.batchId(),
                Map.of(
                        "expected", "0",
                        "processed", "0",
                        "status", "PENDING"
                )
        );
        verify(balanceTemplate).expire("ledger:batch:meta:" + token.batchId(), Duration.ofMinutes(10));
    }

    @Test
    void setBatchExpectedCount_shouldRejectNegativeCount() {
        assertThatThrownBy(() -> service.setBatchExpectedCount("batch-neg", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expectedCount must be >= 0");
    }

    @Test
    void setBatchExpectedCount_shouldExecuteScriptAndExpire() {
        when(balanceTemplate.execute(any(), any(List.class), any(), any())).thenReturn(1L);

        service.setBatchExpectedCount("batch-12", 8);

        verify(balanceTemplate).execute(
                any(),
                eq(List.of("ledger:batch:meta:batch-12", "ledger:stream:batch:done")),
                eq("batch-12"),
                eq("8")
        );
        verify(balanceTemplate).expire("ledger:batch:meta:batch-12", Duration.ofMinutes(10));
    }

    @Test
    void syncRedisBalances_shouldNoOpForEmptyMap() {
        service.syncRedisBalances(Map.of());

        verify(balanceTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void syncRedisBalances_shouldNormalizeAmountsAndExecuteSettleScript() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        Map<UUID, BigDecimal> netChanges = new LinkedHashMap<>();
        netChanges.put(account1, new BigDecimal("10"));
        netChanges.put(account2, new BigDecimal("-2.5"));

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK", "OK");
                });

        service.syncRedisBalances(netChanges);

        verify(redisOperations).execute(
                any(),
                eq(List.of("ledger:db:snapshot", "ledger:pending:delta")),
                eq(account1.toString()),
                eq("10.0000")
        );
        verify(redisOperations).execute(
                any(),
                eq(List.of("ledger:db:snapshot", "ledger:pending:delta")),
                eq(account2.toString()),
                eq("-2.5000")
        );
    }

    @Test
    void readNewFromStream_shouldReturnParsedEnvelopes() {
        TransactionRequest request = request(TransactionType.TRANSFER, "25.0000");
        String payload = payloadJson(request, "25.0000");

        MapRecord<String, Object, Object> mapRecord = StreamRecords.newRecord()
                .in("ledger:stream:tx")
                .withId(RecordId.of("11-0"))
                .ofMap(Map.of(
                        "payload", payload,
                        "batchId", "batch-11"
                ));

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(mapRecord));

        List<StreamEnvelope<TransactionRequest>> result = service.readNewFromStream(10, Duration.ofMillis(100));

        assertThat(result).hasSize(1);
        StreamEnvelope<TransactionRequest> envelope = result.getFirst();
        assertThat(envelope.streamId()).isEqualTo("11-0");
        assertThat(envelope.batchId()).isEqualTo("batch-11");
        assertThat(envelope.deliveryCount()).isEqualTo(1);
        assertThat(envelope.rawJson()).isEqualTo(payload);
        assertThat(envelope.data().getReferenceId()).isEqualTo(request.getReferenceId());
    }

    @Test
    void readNewFromStream_shouldReturnEmptyWhenStreamReturnsNull() {
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(null);

        List<StreamEnvelope<TransactionRequest>> result = service.readNewFromStream(10, Duration.ofMillis(100));

        assertThat(result).isEmpty();
    }

    @Test
    void readNewFromStream_shouldMoveBadPayloadToDlqAndSkipEnvelope() {
        MapRecord<String, Object, Object> badRecord = StreamRecords.newRecord()
                .in("ledger:stream:tx")
                .withId(RecordId.of("12-0"))
                .ofMap(Map.of(
                        "payload", "not-json",
                        "batchId", "batch-12"
                ));

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(badRecord));

        List<StreamEnvelope<TransactionRequest>> result = service.readNewFromStream(10, Duration.ofMillis(100));

        assertThat(result).isEmpty();
        verify(streamOperations).add(eq("ledger:stream:tx:dlq"), argThat(map ->
                "12-0".equals(map.get("streamId")) &&
                        "not-json".equals(map.get("payload")) &&
                        String.valueOf(map.get("reason")).startsWith("PARSE_ERROR:")
        ));
        verify(streamOperations).acknowledge("ledger:stream:tx", "ledger-stream-group", RecordId.of("12-0"));
    }

    @Test
    void claimStaleFromStream_shouldReturnEmptyWhenNoPendingMessages() {
        PendingMessages pendingMessages = mock(PendingMessages.class);
        when(pendingMessages.isEmpty()).thenReturn(true);

        when(streamOperations.pending(
                "ledger:stream:tx",
                "ledger-stream-group",
                Range.unbounded(),
                5L
        )).thenReturn(pendingMessages);

        List<StreamEnvelope<TransactionRequest>> result =
                service.claimStaleFromStream(5, Duration.ofSeconds(5));

        assertThat(result).isEmpty();
        verify(streamOperations, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    void claimStaleFromStream_shouldReturnEmptyWhenNoMessagesMeetMinIdle() {
        PendingMessage freshPending = mock(PendingMessage.class);
        when(freshPending.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofSeconds(1));

        PendingMessages pendingMessages = mock(PendingMessages.class);
        when(pendingMessages.isEmpty()).thenReturn(false);
        when(pendingMessages.iterator()).thenReturn(List.of(freshPending).iterator());

        when(streamOperations.pending(
                "ledger:stream:tx",
                "ledger-stream-group",
                Range.unbounded(),
                5L
        )).thenReturn(pendingMessages);

        List<StreamEnvelope<TransactionRequest>> result =
                service.claimStaleFromStream(5, Duration.ofSeconds(5));

        assertThat(result).isEmpty();
        verify(streamOperations, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    void claimStaleFromStream_shouldClaimAndParseEligibleMessages() {
        TransactionRequest request = request(TransactionType.TRANSFER, "15.0000");
        String payload = payloadJson(request, "15.0000");

        PendingMessage stalePending = mock(PendingMessage.class);
        when(stalePending.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofSeconds(10));
        when(stalePending.getId()).thenReturn(RecordId.of("21-0"));
        when(stalePending.getTotalDeliveryCount()).thenReturn(4L);

        PendingMessages pendingMessages = mock(PendingMessages.class);
        when(pendingMessages.isEmpty()).thenReturn(false);
        when(pendingMessages.iterator()).thenReturn(List.of(stalePending).iterator());

        MapRecord<String, Object, Object> claimed = StreamRecords.newRecord()
                .in("ledger:stream:tx")
                .withId(RecordId.of("21-0"))
                .ofMap(Map.of(
                        "payload", payload,
                        "batchId", "batch-21"
                ));

        when(streamOperations.pending(
                "ledger:stream:tx",
                "ledger-stream-group",
                Range.unbounded(),
                5L
        )).thenReturn(pendingMessages);

        when(streamOperations.claim(
                eq("ledger:stream:tx"),
                eq("ledger-stream-group"),
                eq("test-consumer"),
                eq(Duration.ofSeconds(5)),
                any(RecordId[].class)
        )).thenReturn(List.of(claimed));

        List<StreamEnvelope<TransactionRequest>> result =
                service.claimStaleFromStream(5, Duration.ofSeconds(5));

        assertThat(result).hasSize(1);
        StreamEnvelope<TransactionRequest> envelope = result.getFirst();
        assertThat(envelope.streamId()).isEqualTo("21-0");
        assertThat(envelope.batchId()).isEqualTo("batch-21");
        assertThat(envelope.deliveryCount()).isEqualTo(4);
        assertThat(envelope.data().getReferenceId()).isEqualTo(request.getReferenceId());
    }

    @Test
    void claimStaleFromStream_shouldDlqWhenPayloadMissing() {
        PendingMessage stalePending = mock(PendingMessage.class);
        when(stalePending.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofSeconds(10));
        when(stalePending.getId()).thenReturn(RecordId.of("22-0"));
        when(stalePending.getTotalDeliveryCount()).thenReturn(1L);

        PendingMessages pendingMessages = mock(PendingMessages.class);
        when(pendingMessages.isEmpty()).thenReturn(false);
        when(pendingMessages.iterator()).thenReturn(List.of(stalePending).iterator());

        MapRecord<String, Object, Object> claimed = StreamRecords.newRecord()
                .in("ledger:stream:tx")
                .withId(RecordId.of("22-0"))
                .ofMap(Map.of(
                        "batchId", "batch-22"
                ));

        when(streamOperations.pending(
                "ledger:stream:tx",
                "ledger-stream-group",
                Range.unbounded(),
                5L
        )).thenReturn(pendingMessages);

        when(streamOperations.claim(
                eq("ledger:stream:tx"),
                eq("ledger-stream-group"),
                eq("test-consumer"),
                eq(Duration.ofSeconds(5)),
                any(RecordId[].class)
        )).thenReturn(List.of(claimed));

        List<StreamEnvelope<TransactionRequest>> result =
                service.claimStaleFromStream(5, Duration.ofSeconds(5));

        assertThat(result).isEmpty();

        verify(streamOperations).add(eq("ledger:stream:tx:dlq"), argThat(map ->
                "22-0".equals(map.get("streamId")) &&
                        "null".equals(map.get("payload")) &&
                        "MISSING_PAYLOAD_STALE".equals(map.get("reason"))
        ));
        verify(streamOperations).acknowledge("ledger:stream:tx", "ledger-stream-group", RecordId.of("22-0"));
    }

    @Test
    void claimStaleFromStream_shouldDlqWhenPayloadCannotBeParsed() {
        PendingMessage stalePending = mock(PendingMessage.class);
        when(stalePending.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofSeconds(10));
        when(stalePending.getId()).thenReturn(RecordId.of("23-0"));
        when(stalePending.getTotalDeliveryCount()).thenReturn(3L);

        PendingMessages pendingMessages = mock(PendingMessages.class);
        when(pendingMessages.isEmpty()).thenReturn(false);
        when(pendingMessages.iterator()).thenReturn(List.of(stalePending).iterator());

        MapRecord<String, Object, Object> claimed = StreamRecords.newRecord()
                .in("ledger:stream:tx")
                .withId(RecordId.of("23-0"))
                .ofMap(Map.of(
                        "payload", "{bad-json",
                        "batchId", "batch-23"
                ));

        when(streamOperations.pending(
                "ledger:stream:tx",
                "ledger-stream-group",
                Range.unbounded(),
                5L
        )).thenReturn(pendingMessages);

        when(streamOperations.claim(
                eq("ledger:stream:tx"),
                eq("ledger-stream-group"),
                eq("test-consumer"),
                eq(Duration.ofSeconds(5)),
                any(RecordId[].class)
        )).thenReturn(List.of(claimed));

        List<StreamEnvelope<TransactionRequest>> result =
                service.claimStaleFromStream(5, Duration.ofSeconds(5));

        assertThat(result).isEmpty();

        verify(streamOperations).add(eq("ledger:stream:tx:dlq"), argThat(map ->
                "23-0".equals(map.get("streamId")) &&
                        "{bad-json".equals(map.get("payload")) &&
                        String.valueOf(map.get("reason")).startsWith("PARSE_ERROR_STALE:")
        ));
        verify(streamOperations).acknowledge("ledger:stream:tx", "ledger-stream-group", RecordId.of("23-0"));
    }

    @Test
    void syncRedisBalances_shouldExecuteOncePerEntry() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        Map<UUID, BigDecimal> netChanges = new LinkedHashMap<>();
        netChanges.put(account1, new BigDecimal("1.0000"));
        netChanges.put(account2, new BigDecimal("2.0000"));

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK", "OK");
                });

        service.syncRedisBalances(netChanges);

        verify(redisOperations, times(2)).execute(
                any(),
                eq(List.of("ledger:db:snapshot", "ledger:pending:delta")),
                any(),
                any()
        );
    }

    @Test
    void processBatchAtomic_shouldComposeIdempotencyKeyUsingReferenceIdAndType() {
        TransactionRequest request = request(TransactionType.TRANSFER, "10.0000");

        when(balanceTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(invocation -> {
                    SessionCallback<?> callback = invocation.getArgument(0);
                    callback.execute(redisOperations);
                    return List.of("OK");
                });

        service.processBatchAtomic(List.of(request), "batch-idem");

        ArgumentCaptor<Object> firstArgCaptor = ArgumentCaptor.forClass(Object.class);

        verify(redisOperations).execute(
                any(),
                any(List.class),
                firstArgCaptor.capture(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );

        assertThat(firstArgCaptor.getValue())
                .isEqualTo(request.getReferenceId() + "-" + request.getType().name());
    }

    private TransactionRequest request(TransactionType type, String amount) {
        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(UUID.randomUUID());
        request.setType(type);
        request.setSenderId(UUID.randomUUID());
        request.setReceiverId(UUID.randomUUID());
        request.setAmount(new BigDecimal(amount));
        request.setCurrency(CurrencyType.USD);
        request.setMetadata("meta");
        request.setDebitAccountId(UUID.randomUUID());
        request.setCreditAccountId(UUID.randomUUID());
        return request;
    }

    private String payloadJson(TransactionRequest request, String amountLiteral) {
        return """
                {
                  "referenceId":"%s",
                  "type":"%s",
                  "senderId":"%s",
                  "receiverId":"%s",
                  "amount":%s,
                  "currency":"%s",
                  "metadata":"%s",
                  "debitAccountId":"%s",
                  "creditAccountId":"%s"
                }
                """.formatted(
                request.getReferenceId(),
                request.getType().name(),
                request.getSenderId(),
                request.getReceiverId(),
                amountLiteral,
                request.getCurrency().name(),
                request.getMetadata(),
                request.getDebitAccountId(),
                request.getCreditAccountId()
        );
    }

    private StreamEnvelope<TransactionRequest> envelope(String streamId) {
        return new StreamEnvelope<>(streamId, "batch-1", "{\"ok\":true}", null, 1);
    }
}