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
            // 1. Parse the incoming Ledger event
            JsonNode payload = objectMapper.readTree(message);
            UUID paymentId = UUID.fromString(payload.get("aggregateId").asText());

            // 2. Fetch the Payment
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));

            // 3. Trigger the actual Bank API call now that funds are safe!
            log.info("Ledger confirmed reservation. Calling bank for payment: {}", paymentId);
            withdrawStrategy.callBankApi(payment); // You might need to expose callBankApi in your strategy

        } catch (Exception e) {
            log.error("Failed to process ledger reservation confirmation", e);
        }
    }
}
