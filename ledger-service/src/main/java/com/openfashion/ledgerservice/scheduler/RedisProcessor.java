package com.openfashion.ledgerservice.scheduler;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.AckResult;
import com.openfashion.ledgerservice.dto.redis.StreamEnvelope;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import com.openfashion.ledgerservice.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@Component
public class RedisProcessor {

    private final RedisService redisService;
    private final LedgerBatchService ledgerBatchService;
    private static final int MAX_ATTEMPTS = 3;

    @Scheduled(fixedDelay = 500)
    public void processQueue() {

        List<StreamEnvelope<TransactionRequest>> stale = redisService.claimStaleFromStream(50, Duration.ofSeconds(30));
        List<StreamEnvelope<TransactionRequest>> fresh = redisService.readNewFromStream(100, Duration.ofSeconds(1));

        if (stale.isEmpty() && fresh.isEmpty()) {
            return;
        }

        Map<String, StreamEnvelope<TransactionRequest>> deduped = Stream.concat(stale.stream(), fresh.stream())
                .collect(Collectors.toMap(
                        StreamEnvelope::streamId,
                        env -> env,
                        (existing, _) -> existing
                ));

        List<StreamEnvelope<TransactionRequest>> messages = deduped.values().stream().toList();

        List<TransactionRequest> dbBatch = messages.stream().map(StreamEnvelope::data).toList();

        try {
            ledgerBatchService.saveTransactions(dbBatch);

            AckResult ack = redisService.acknowledgePersisted(messages);

            if (!ack.success()) {
                throw new IllegalStateException(
                        "Ack failed: requested = " + ack.requested() + ", acked = " + ack.acked() +
                                ", missingIds = " + ack.missingIds().size() + ", error = " + ack.error()
                );
            }

            markBatchProgress(messages);

            log.debug("Processed {} stream entries (stale = {}, fresh = {}",
                    messages.size(), stale.size(), fresh.size());

        } catch (Exception e) {
            // Intentionally leave entries unacked for reclaim/retry.
            log.error("Failed processing stream batch; entries remain pending. size = {}", messages.size(), e);

            for (StreamEnvelope<TransactionRequest> message : messages) {
                processOneWithRetryCutoff(message, MAX_ATTEMPTS);
            }
        }

    }

    private void processOneWithRetryCutoff(StreamEnvelope<TransactionRequest> message, int maxAttempts) {
        try {
            ledgerBatchService.saveTransactions(List.of(message.data()));

            AckResult singleAck = redisService.acknowledgePersisted(List.of(message));

            if (!singleAck.success()) {
                // Leave pending for reclaim; do not DLQ on ack infrastructure issues
                log.error("Single-message ack failed for streamId={} requested={} acked={} error={}",
                        message.streamId(), singleAck.requested(), singleAck.acked(), singleAck.error());
                return;
            }

            markBatchProgress(List.of(message));
        } catch (Exception e) {


            if (message.deliveryCount() >= maxAttempts) {
                redisService.moveToDlqAndAck(
                        message,
                        "RETRY_LIMIT_EXCEEDED attempts = " + message.deliveryCount() + " error = " + e.getMessage()
                );

                log.error("Moved to DLQ after {} attempts. streamId = {}", message.deliveryCount(), message.streamId());
                return;
            }

            log.warn("Single message processing failed, leaving pending. streamId = {} attempts = {} maxAttempts = {}",
                    message.streamId(), message.deliveryCount(), maxAttempts, e);
        }
    }

    private void markBatchProgress(List<StreamEnvelope<TransactionRequest>> ackedMessages) {
        Map<String, Long> ackedByBatch = ackedMessages.stream()
                .filter(m -> m.batchId() != null && !m.batchId().isBlank())
                .collect(Collectors.groupingBy(StreamEnvelope::batchId, Collectors.counting()));

        ackedByBatch.forEach((batchId, count) ->
                redisService.markBatchProgress(batchId, count.intValue()));
    }


}
