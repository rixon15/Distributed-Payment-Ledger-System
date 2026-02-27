package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

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

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.WITHDRAWAL;
    }

    @Override
    public void execute(Payment payment, PaymentRequest request) {
        finalizeStatus(payment, PaymentStatus.PENDING, null);

        CompletableFuture.runAsync(() -> callBankApi(payment));
    }

    public void callBankApi(Payment payment) {

       try {
           BankPaymentResponse existingStatus = checkExternalStatus(payment.getId().toString());

           reconcileWithBank(payment, existingStatus, bankUrl);
       } catch (Exception e) {
           log.error("Reconciliation inquiry failed for payment {}. Retrying later.", payment.getId(), e);
       }
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
