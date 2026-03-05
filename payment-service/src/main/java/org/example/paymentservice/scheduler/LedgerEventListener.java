package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.model.Payment;
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

    @KafkaListener(topics = "ledger.funds.reserved", groupId = "payment-group")
    public void onFundsReserved(String message) {
        try {
            JsonNode payload = objectMapper.readTree(message);

            if (payload.isTextual()) {
                payload = objectMapper.readTree(payload.asText());
            }

            // 1. SAFELY EXTRACT THE RAW STRING (NO QUOTES)
            String refIdStr = payload.path("referenceId").asText(null);

            // 2. CHECK IF IT WAS MISSING
            if (refIdStr == null || refIdStr.isBlank()) {
                log.warn("Discarding invalid message: missing 'referenceId'. Payload: {}", message);
                return; // Discard and acknowledge so Kafka moves on
            }

            // 3. PARSE THE CLEAN UUID
            UUID paymentId = UUID.fromString(refIdStr);

            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new RuntimeException("Payment not found in DB: " + paymentId));

            log.info("Ledger confirmed reservation. Calling bank for payment: {}", paymentId);
            withdrawStrategy.reconcilePaymentWithBank(payment);

        } catch (IllegalArgumentException _) {
            log.error("Discarding message with invalid UUID format. Payload: {}", message);
        } catch (Exception e) {
            log.error("System error processing reservation confirmation", e);
            throw new RuntimeException("Failed to process ledger reservation confirmation", e);
        }
    }
}
