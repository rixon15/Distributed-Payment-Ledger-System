package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Strategy for withdrawal lifecycle orchestration.
 *
 * <p>Initial execution emits pending reserve intent; final authorization/failure
 * is decided later from ledger response + bank reconciliation.
 */
@Component
@Slf4j
public class WithdrawStrategy extends PaymentStrategy {

    private final String bankUrl;

    public WithdrawStrategy(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                            ObjectMapper objectMapper, TransactionTemplate tx, RestClient restClient,
                            @Value("${app.bank.url}") String bankUrl) {
        super(paymentRepository, outboxRepository, objectMapper, tx, restClient);
        this.bankUrl = bankUrl;
    }

    /**
     * Supports WITHDRAWAL payment type.
     */
    @Override
    public boolean supports(PaymentType type) {
        return type == PaymentType.WITHDRAWAL;
    }

    /**
     * Starts withdrawal by publishing pending state to outbox.
     */
    @Override
    public void execute(Payment payment, PaymentRequest request) {
        finalizeStatus(payment, PaymentStatus.PENDING, null);
    }

    private void callBankApi(Payment payment) {

        try {
            BankPaymentResponse existingStatus = checkExternalStatus(payment.getId().toString());

            reconcileWithBank(payment, existingStatus, bankUrl);
        } catch (Exception e) {
            log.error("Reconciliation inquiry failed for payment {}. Retrying later.", payment.getId(), e);
        }
    }

    /**
     * Reconciles payment against bank status endpoint.
     */
    public void reconcilePaymentWithBank(Payment payment) {
        callBankApi(payment);
    }

    private BankPaymentResponse checkExternalStatus(String paymentId) {
        try {
            return restClient.get()
                    .uri(bankUrl + "/status/" + paymentId)
                    .retrieve()
                    .body(BankPaymentResponse.class);
        } catch (Exception e) {
            log.error("Failed to fetch external status for payment {}", paymentId, e);
            return null;
        }
    }
}
