package org.example.paymentservice.service.implementation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.core.exception.InvalidTransferException;
import org.example.paymentservice.core.exception.PaymentNotFoundException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.*;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.service.RequestLockService;
import org.example.paymentservice.service.strategy.PaymentStrategy;
import org.example.paymentservice.simulator.riskengine.dto.RiskRequest;
import org.example.paymentservice.simulator.riskengine.dto.RiskResponse;
import org.example.paymentservice.simulator.riskengine.dto.RiskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImp implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final List<PaymentStrategy> strategyList;
    private final RestClient restClient;
    private final TransactionTemplate tx;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final RequestLockService requestLockService;


    private final Map<TransactionType, PaymentStrategy> strategyMap = new EnumMap<>(TransactionType.class);

    @Value("${app.risk-engine.url}")
    private String riskEngineUrl;

    //We created this map to have O(1) lookup instead of O(n)
    @PostConstruct
    public void initStrategies() {
        log.info("Initializing Payment Strategies...");

        for (TransactionType type : TransactionType.values()) {
            strategyList.stream()
                    .filter(strategy -> strategy.supports(type))
                    .findFirst()
                    .ifPresentOrElse(
                            strategy -> strategyMap.put(type, strategy),
                            () -> log.warn("No strategy found for TransactionType: {}", type)
                    );
        }

        log.info("Strategy Map built with {} entries", strategyMap.size());
    }

    @Override
    public void processPayment(UUID senderId, PaymentRequest request) {

        PaymentStrategy strategy = strategyMap.get(request.type());

        if (strategy == null) {
            throw new UnsupportedOperationException("No strategy found for type: " + request.type());
        }

        if (request.type() == TransactionType.TRANSFER && senderId.equals(request.receiverId())) {
            log.warn("Blocked self-transfer attempt for user: {}", senderId);
            throw new InvalidTransferException("You cannot transfer money to your own account.");
        }

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
            throw new DuplicatedRequestException("Payment already processed with key: " + request.idempotencyKey());
        }



        try {

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

            if (riskResult.status() == RiskStatus.MANUAL_REVIEW) {
                handleManualReview(payment, riskResult.reason());
                return;
            }

            strategy.execute(payment, request);
        } catch (Exception e) {
            log.error("Payment processing failed for key: {}. Releasing Redis lock", request.idempotencyKey());
            throw e;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resumeProcessing(UUID paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        log.info("Resuming processing for payment {}", payment.getId());

        if (payment.getStatus() != PaymentStatus.RECOVERING && payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("Payment {} is not in a resumable state (Status: {}). Skipping.", paymentId, payment.getStatus());
            return;
        }
        PaymentRequest request = payment.mapToRequest();

        PaymentStrategy strategy = strategyList.stream()
                .filter(s -> s.supports(payment.getType()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException("Strategy not supported"));

        RiskResponse riskResult = checkRisk(payment.getUserId(), request);
        log.info("Rechecked risk result for payment {}: {}", payment.getId(), riskResult.status());

        if (riskResult.status() == RiskStatus.REJECTED) {
            handleRiskFailure(payment, riskResult.reason());
            return;
        }

        strategy.execute(payment, request);
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

        requestLockService.release(payment.getIdempotencyKey());
    }

    private void handleManualReview(Payment payment, String reason) {
        log.warn("Payment {} flagged for MANUAL REVIEW: {}", payment.getId(), reason);

        payment.setStatus(PaymentStatus.MANUAL_REVIEW);
        payment.setErrorMessage(reason);
        payment.setUpdatedAt(LocalDateTime.now());

        paymentRepository.save(payment);

        outboxRepository.save(
                OutboxEvent.builder()
                        .aggregateId(payment.getId().toString())
                        .eventType("PAYMENT_HELD_FOR_REVIEW")
                        .status(OutboxStatus.PENDING)
                        .payload(objectMapper.writeValueAsString(payment))
                        .createdAt(Instant.now())
                        .build()
        );
    }

    @Transactional
    public List<UUID> claimStuckPayments(int batchSize) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);

        List<Payment> stuckPayments = paymentRepository.findStuckPaymentsForRecovery(threshold, batchSize);

        List<UUID> claimedIds = new ArrayList<>();

        for (Payment p : stuckPayments) {
            p.setStatus(PaymentStatus.RECOVERING);
            p.setUpdatedAt(LocalDateTime.now());
            claimedIds.add(p.getId());
        }

        paymentRepository.saveAll(stuckPayments);

        return claimedIds;
    }
}
