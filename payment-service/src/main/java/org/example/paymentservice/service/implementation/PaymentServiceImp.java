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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Main payment orchestration service.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>validate idempotency and persist initial payment record,</li>
 *   <li>execute risk check,</li>
 *   <li>dispatch to type-specific strategy,</li>
 *   <li>handle recovery of stuck pending payments.</li>
 * </ul>
 */
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


    private final Map<PaymentType, PaymentStrategy> strategyMap = new EnumMap<>(PaymentType.class);

    @Value("${app.risk-engine.url}")
    private String riskEngineUrl;

    /**
     * Builds enum-dispatch strategy map at startup.
     */
    @PostConstruct
    public void initStrategies() {
        log.info("Initializing Payment Strategies...");

        for (PaymentType type : PaymentType.values()) {
            strategyList.stream()
                    .filter(strategy -> strategy.supports(type))
                    .findFirst()
                    .ifPresentOrElse(
                            strategy -> strategyMap.put(type, strategy),
                            () -> log.warn("No strategy found for PaymentType: {}", type)
                    );
        }

        log.info("Strategy Map built with {} entries", strategyMap.size());
    }

    /**
     * Validates and orchestrates a new payment request end-to-end.
     *
     * <p>Creates a pending payment row first, then performs risk screening and strategy execution.
     */
    @Override
    public void processPayment(UUID senderId, PaymentRequest request) {

        PaymentStrategy strategy = strategyMap.get(request.type());

        if (strategy == null) {
            throw new UnsupportedOperationException("No strategy found for type: " + request.type());
        }

        if (request.type() == PaymentType.TRANSFER && senderId.equals(request.receiverId())) {
            log.warn("Blocked self-transfer attempt for user: {}", senderId);
            throw new InvalidTransferException("You cannot transfer money to your own account.");
        }

        UUID receiverId = (request.type() == PaymentType.DEPOSIT ||
                request.type() == PaymentType.WITHDRAWAL) ? senderId : request.receiverId();

        log.info("Processing payment request for user: {} with idempotencyKey: {}", senderId, request.idempotencyKey());

        paymentRepository.findByIdempotencyKey(request.idempotencyKey()).ifPresent(_ -> {
            log.warn("Payment already exists in the DB for key: {}", request.idempotencyKey());
            throw new DuplicatedRequestException("Payment already processed with key: " + request.idempotencyKey());
        });

        Payment payment;

        try {
            payment = tx.execute(_ -> {
                Payment newPayment = Payment.builder()
                        .userId(senderId)
                        .receiverId(receiverId)
                        .type(request.type())
                        .idempotencyKey(request.idempotencyKey())
                        .amount(request.amount())
                        .currency(CurrencyType.valueOf(request.currency().toUpperCase()))
                        .status(PaymentStatus.PENDING)
                        .createdAt(LocalDateTime.now())
                        .build();

                return paymentRepository.saveAndFlush(newPayment);
            });

        } catch (DataIntegrityViolationException e) {
            if (isIdempotencyConstraintViolation(e)) {
                log.warn("Concurrent duplicate payment blocked by DB constraint for key: {}", request.idempotencyKey());
                throw new DuplicatedRequestException(request.idempotencyKey());
            }

            log.error("Database integrity violation while persisting initial payment record.");
            throw e;
        }

        try {
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
            log.error("Payment processing business logic failed for key: {}. Releasing Redis lock", request.idempotencyKey(), e);
            throw e;
        }

    }

    /**
     * Retries processing for payments marked as recoverable.
     */
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

    /**
     * Claims stale pending payments and marks them as RECOVERING.
     */
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

    private boolean isIdempotencyConstraintViolation(DataIntegrityViolationException e) {
        String rootMessage = e.getMostSpecificCause().getMessage();
        // '23505' is the Postgres SQLState for unique violation
        return rootMessage != null &&
                (rootMessage.contains("23505") || rootMessage.contains("uc_payments_idempotency_key"));
    }

    /**
     * Calls risk engine and normalizes failure into rejected risk response.
     */
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

    /**
     * Marks payment as failed due to risk rejection and releases idempotency lock.
     */
    private void handleRiskFailure(Payment payment, String reason) {
        tx.executeWithoutResult(_ -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage("Risk rejected: " + reason);
            paymentRepository.save(payment);
        });

        requestLockService.release(payment.getIdempotencyKey());
    }

    /**
     * Marks payment for manual review and emits outbox event for downstream handling.
     */
    private void handleManualReview(Payment payment, String reason) {
        log.warn("Payment {} flagged for MANUAL REVIEW: {}", payment.getId(), reason);

        payment.setStatus(PaymentStatus.MANUAL_REVIEW);
        payment.setErrorMessage(reason);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        //TODO: implement a way to handle the payments manually
        outboxRepository.save(
                OutboxEvent.builder()
                        .aggregateId(payment.getId().toString())
                        .eventType(payment.getType())
                        .payload(objectMapper.writeValueAsString(payment))
                        .createdAt(Instant.now())
                        .build()
        );
    }
}
