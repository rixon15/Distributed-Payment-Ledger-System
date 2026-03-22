package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.core.exception.PaymentNotFoundException;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.strategy.WithdrawStrategy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class LedgerEventListener {

    private final PaymentRepository paymentRepository;
    private final WithdrawStrategy withdrawStrategy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "transaction.response", groupId = "payment-group")
    public void onLedgerResponse(String message) {
        try {
            JsonNode eventNode = objectMapper.readTree(message);

            if (eventNode.isString()) {
                eventNode = objectMapper.readTree(eventNode.asString());
            }

            String type = eventNode.path("type").asString(null);

            if (!"WITHDRAWAL_RESERVE".equals(type)) {
                return;
            }

            String refIdStr = eventNode.path("referenceId").asString(null);

            if (refIdStr == null || refIdStr.isBlank()) {
                log.warn("Discarding invalid message: missing referenceId. Payload: {}", message);
                return;
            }

            UUID paymentId = UUID.fromString(refIdStr);
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));

            if (payment.getStatus() != PaymentStatus.PENDING) {
                log.info("Payment {} is already in status {}. Skipping bank call", paymentId, payment.getStatus());
                return;
            }

            log.info("Ledger confirmed WITHDRAWAL_RESERVE. Calling bank for payment: {}", paymentId);
            withdrawStrategy.reconcilePaymentWithBank(payment);
        } catch (IllegalArgumentException _) {
            log.error("Discarding message with invalid UUID format. Payload: {}", message);
        } catch (Exception e) {
            log.error("System error processing ledger response", e);
            throw new RuntimeException("Failed to process ledger response", e);
        }
    }
}
