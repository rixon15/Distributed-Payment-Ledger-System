package org.example.paymentservice.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.core.exception.RiskEngineException;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    @Value("${app.risk-engine.url}")
    private String riskEngineUrl;

    @Override
    public void processPayment(UUID senderId, PaymentRequest request) {

        //check idempotency key


        if(!acquire(request.idempotencyKey(), 300)) {
            throw new DuplicatedRequestException(request.idempotencyKey());
        }

        //Check if strategy exists

        strategyList.stream()
                .filter(s -> s.supports(request.type()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

        //Create and save payment intent

        Payment payment = Payment.builder()
                .userId(senderId)
                .receiverId(request.receiverId())
                .type(request.type())
                .idempotencyKey(request.idempotencyKey())
                .amount(request.amount())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();


        //check risk engine result

        //Delegate action to specific strategy

        //update status and create outbox entry
    }

    private boolean acquire(String key, long ttlSeconds) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = "1".getBytes(StandardCharsets.UTF_8);

        Boolean success = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        keyBytes,
                        valueBytes,
                        Expiration.seconds(ttlSeconds),
                        RedisStringCommands.SetOption.SET_IF_ABSENT
                ));

        return Boolean.TRUE.equals(success);
    }

    private RiskResponse riskCheck(UUID senderId, PaymentRequest request, Payment payment) {

        RiskResponse risk;

        try {
            risk = restClient.post()
                    .uri(riskEngineUrl + "/evaluate")
                    .body(new RiskRequest(senderId, request.amount(), "user-ip-address"))
                    .retrieve()
                    .body(RiskResponse.class);

            if(risk == null || !risk.status().toString().equals("APPROVED")) {
                paymentRepository.save(payment.update(
                        "Risk Rejected: " + (risk != null ? risk.reason() : "Unknown"),
                        PaymentStatus.FAILED));
                throw new RiskEngineException(
                        "Risk Rejected: " + (risk != null ? risk.reason() : "Unknown"),
                        HttpStatus.CONFLICT);
            }
        } catch (Exception e) {
            log.error("Risk Engine HTTP Failure", e);
            throw new RiskEngineException("Technical Error: Risk Engine Unreachable", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return risk;
    }

}
