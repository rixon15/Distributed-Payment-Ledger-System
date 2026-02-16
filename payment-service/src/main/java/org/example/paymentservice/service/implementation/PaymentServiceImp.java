package org.example.paymentservice.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImp implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final List<PaymentStrategy> strategyList;
    private final RedisTemplate<String, String> redisTemplate;
    private final RestClient restClient;
    private final TransactionTemplate tx;

    @Value("${app.risk-engine.url}")
    private String riskEngineUrl;

    @Override
    public void processPayment(UUID senderId, PaymentRequest request) {

        UUID receiverId;

        if (request.type() == TransactionType.DEPOSIT || request.type() == TransactionType.WITHDRAWAL) {
            receiverId = senderId;
        } else {
            receiverId = request.receiverId();
        }

        log.info("Processing payment request for user: {} with idempotencyKey: {}", senderId, request.idempotencyKey());

        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(request.idempotencyKey());

        if (existingPayment.isPresent()) {
            log.warn("Payment already exists in the DB for key: {}", request.idempotencyKey());
            return;
        }

        checkDuplicatedRequest(request.idempotencyKey());

        try {
            PaymentStrategy strategy = strategyList.stream()
                    .filter(s -> s.supports(request.type()))
                    .findFirst()
                    .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

            Payment payment = tx.execute(_ -> paymentRepository.save(
                    Payment.builder()
                            .userId(senderId)
                            .receiverId(receiverId)
                            .type(request.type())
                            .idempotencyKey(request.idempotencyKey())
                            .amount(request.amount())
                            .currency(CurrencyType.valueOf(request.currency().toUpperCase()))
                            .status(PaymentStatus.PENDING)
                            .createdAt(LocalDateTime.now())
                            .build()
            ));

            RiskResponse riskResult = checkRisk(senderId, request);
            if (riskResult.status() == RiskStatus.REJECTED) {
                handleRiskFailure(payment, riskResult.reason());
                return;
            }

            strategy.execute(payment, request);
        } catch (Exception e) {
            log.error("Payment processing failed for key: {}. Releasing Redis lock", request.idempotencyKey());
            redisTemplate.delete(request.idempotencyKey());
            throw e;
        }
    }

    public void resumeProcessing(Payment payment) {
        log.info("Retrying payment request for user: {} with idempotencyKey: {}", payment.getUserId(), payment.getIdempotencyKey());

        checkDuplicatedRequest(payment.getIdempotencyKey());

        PaymentRequest request = payment.mapToRequest();

        PaymentStrategy strategy = strategyList.stream()
                .filter(s -> s.supports(payment.getType()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

        RiskResponse riskResult = checkRisk(payment.getUserId(), request);
        log.info("Rechecked risk result for payment {}: {}", payment.getId(), riskResult.status());

        strategy.execute(payment, request);
    }

    private boolean acquire(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = "1".getBytes(StandardCharsets.UTF_8);

        Boolean success = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        keyBytes,
                        valueBytes,
                        Expiration.seconds(300),
                        RedisStringCommands.SetOption.SET_IF_ABSENT
                ));

        return Boolean.FALSE.equals(success);
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

    private void handleRiskFailure(Payment payment, String reason) {
        tx.executeWithoutResult(_ -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage("Risk rejected: " + reason);
            paymentRepository.save(payment);
        });

        redisTemplate.delete(payment.getIdempotencyKey());
    }

    private void checkDuplicatedRequest(String idempotencyKey) {
        if (acquire(idempotencyKey)) {
            log.warn("Duplicate payment request blocked. Key: {}", idempotencyKey);
            throw new DuplicatedRequestException(idempotencyKey);
        }
    }

}
