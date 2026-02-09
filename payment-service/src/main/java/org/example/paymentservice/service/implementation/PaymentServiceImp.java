package org.example.paymentservice.service.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.SagaActionResult;
import org.example.paymentservice.dto.event.TransactionInitiatedEvent;
import org.example.paymentservice.dto.event.TransactionPayload;
import org.example.paymentservice.model.OutboxEvent;
import org.example.paymentservice.model.OutboxStatus;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImp implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final List<PaymentStrategy> strategyList;
    private final RedisTemplate<String, String> redisTemplate;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxRepository outboxRepository;
    private final TransactionTemplate tx;

    @Value("${app.risk-engine.url}")
    private String riskEngineUrl;

    @Override
    public void processPayment(UUID senderId, PaymentRequest request) {

        log.info("Processing payment request for user: {} with idempotencyKey: {}", senderId, request.idempotencyKey());

        if (!acquire(request.idempotencyKey())) {
            log.warn("Duplicate payment request blocked. Key: {}", request.idempotencyKey());
            throw new DuplicatedRequestException(request.idempotencyKey());
        }

        PaymentStrategy strategy = strategyList.stream()
                .filter(s -> s.supports(request.type()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

        Payment payment = tx.execute(status -> paymentRepository.save(
                Payment.builder()
                        .userId(senderId)
                        .receiverId(request.receiverId())
                        .type(request.type())
                        .idempotencyKey(request.idempotencyKey())
                        .amount(request.amount())
                        .status(PaymentStatus.PENDING)
                        .createdAt(LocalDateTime.now())
                        .build()
        ));

        RiskResponse riskResult = checkRisk(senderId, request);
        log.info("Risk check result for payment {}: {}", payment.getId(), riskResult.status());

        switch (riskResult.status()) {
            case APPROVED -> executePaymentStrategy(strategy, payment, senderId, request);
            case MANUAL_REVIEW -> handleManualReview(payment, riskResult.reason());
            case REJECTED -> handleFailure(payment,
                    "Risk Rejected: " + riskResult.reason(),
                    "Transaction declined for security reasons.");
        }
    }

    public void resumeProcessing(Payment payment) {
        log.info("Retrying payment request for user: {} with idempotencyKey: {}", payment.getUserId(), payment.getIdempotencyKey());

        PaymentRequest request = payment.mapToRequest();

        PaymentStrategy strategy = strategyList.stream()
                .filter(s -> s.supports(payment.getType()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

        RiskResponse riskResult = checkRisk(payment.getUserId(), request);
        log.info("Rechecked risk result for payment {}: {}", payment.getId(), riskResult.status());

        switch (riskResult.status()) {
            case APPROVED -> executePaymentStrategy(strategy, payment, payment.getUserId(), request);
            case MANUAL_REVIEW -> handleManualReview(payment, riskResult.reason());
            case REJECTED -> handleFailure(payment,
                    "Risk Rejected: " + riskResult.reason(),
                    "Transaction declined for security reasons.");
        }
    }

    private boolean acquire(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = "1".getBytes(StandardCharsets.UTF_8);

        Boolean success = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        keyBytes,
                        valueBytes,
                        Expiration.seconds(86400),
                        RedisStringCommands.SetOption.SET_IF_ABSENT
                ));

        return Boolean.TRUE.equals(success);
    }

    private RiskResponse checkRisk(UUID senderId, PaymentRequest request) {
        try {
            RiskResponse response = restClient.post()
                    .uri(riskEngineUrl + "/evaluate")
                    .body(new RiskRequest(senderId, request.amount(), "user-ip"))
                    .retrieve()
                    .body(RiskResponse.class);

            return response != null ? response : new RiskResponse(RiskStatus.REJECTED, "Empty Response");
        } catch (Exception e) {
            log.error("Risk Engine unreachable", e);
            return new RiskResponse(RiskStatus.REJECTED, "Risk Service Unavailable");
        }
    }

    private void handleFailure(Payment payment, String internalReason, String userMessage) {
        log.warn("Payment {} failed: {}", payment.getId(), internalReason);

        tx.executeWithoutResult(status -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage(internalReason);
            paymentRepository.save(payment);

            saveOutboxEvent(payment, "TRANSACTION_FAILED", userMessage);
        });
    }

    private void handleManualReview(Payment payment, String riskReason) {
        log.info("Payment {} held for manual review. Reason: {}", payment.getId(), riskReason);

        tx.executeWithoutResult(status -> {
            payment.setStatus(PaymentStatus.MANUAL_REVIEW);
            payment.setErrorMessage(riskReason);
            paymentRepository.save(payment);

            saveOutboxEvent(payment, "PAYMENT_HELD", "Your transaction is under review");
        });
    }

    private void executePaymentStrategy(PaymentStrategy strategy, Payment payment, UUID senderId, PaymentRequest request) {
        SagaActionResult result = strategy.performAction(senderId, request);

        tx.executeWithoutResult(status -> {
            if (result.success()) {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setExternalTransactionId(result.externalId() != null ? result.externalId().toString() : null);
                paymentRepository.save(payment);
                saveOutboxEvent(payment, "TRANSACTION_INITIATED", null);
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setErrorMessage(result.errorMessage());
                paymentRepository.save(payment);
                saveOutboxEvent(payment, "TRANSACTION_FAILED", "Transaction failed: " + result.errorMessage());
            }
        });
    }

    private void saveOutboxEvent(Payment payment, String eventType, String userMessage) {
        // Create Payload
        TransactionPayload payloadData = new TransactionPayload(
                payment.getType(),
                payment.getUserId(),
                payment.getReceiverId(),
                payment.getAmount(),
                payment.getCurrency().toString(),
                userMessage,
                null
        );

        TransactionInitiatedEvent eventPayload = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                eventType,
                payment.getId().toString(),
                Instant.now(),
                payloadData
        );

        try {
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateId(payment.getId().toString())
                    .eventType(eventType)
                    .payload(jsonPayload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outbox);
        } catch (Exception e) {
            throw new SerializationFailedException("Failed to serialize event", e);
        }
    }

}
