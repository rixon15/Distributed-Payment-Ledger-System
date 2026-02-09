package org.example.paymentservice.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
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
        log.info("Checked risk result for payment {}: {}", payment.getId(), riskResult.status());

        strategy.execute(payment, request);
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

        strategy.execute(payment, request);
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

}
