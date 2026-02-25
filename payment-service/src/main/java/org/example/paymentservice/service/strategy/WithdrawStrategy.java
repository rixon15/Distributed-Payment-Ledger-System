package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
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
                            @Value("${app.ledger.url}") String ledgerUrl,
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

    private void callBankApi(Payment payment) {
        BankPaymentRequest bankRequest = new BankPaymentRequest(
                payment.getId(),
                "EXT-ACCT-" + payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency().getCode()
        );

        try {
            BankPaymentResponse bankResult = restClient.post()
                    .uri(bankUrl + "/pay")
                    .body(bankRequest)
                    .retrieve()
                    .body(BankPaymentResponse.class);

            if (bankResult != null && bankResult.status() == BankPaymentStatus.APPROVED) {
                finalizeStatus(payment, PaymentStatus.AUTHORIZED, bankResult.transactionId());
            } else {
                handleFailure(payment, "Bank Declined", bankResult != null ? bankResult.reasonCode() : "Unknown");
            }
        } catch (Exception e) {
            log.error("Bank call failed for payment {}", payment.getId(), e);
            // Leave as PENDING; Recovery scheduler will retry bank call later
        }
    }
}
