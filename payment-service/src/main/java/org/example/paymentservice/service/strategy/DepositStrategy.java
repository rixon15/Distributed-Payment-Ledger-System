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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class DepositStrategy extends PaymentStrategy {

    private final String bankUrl;

    public DepositStrategy(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                           ObjectMapper objectMapper, TransactionTemplate tx,
                           RestClient restClient,
                           @Value("${app.bank.url}") String bankUrl) {
        super(paymentRepository, outboxRepository, objectMapper, tx, restClient);
        this.bankUrl = bankUrl;
    }


    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.DEPOSIT;
    }

    @Override
    public void execute(Payment payment, PaymentRequest request) {
        log.info("Initiating Deposit for user: {}", payment.getUserId());

        BankPaymentRequest bankRequest = new BankPaymentRequest(
                payment.getId(), //Unique ref for the bank
                "src_card_" + payment.getUserId(), // Mocked source account token
                request.amount(),
                request.currency()
        );
        BankPaymentResponse response = restClient.post()
                .uri(bankUrl + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bankRequest)
                .retrieve()
                .body(BankPaymentResponse.class);

        if (response == null) {
            throw new IllegalStateException("Failed to call the bank api");
        }

        if (response.status().equals(BankPaymentStatus.APPROVED)) {
            finalizeStatus(payment, PaymentStatus.AUTHORIZED, response.transactionId());
        } else {
            handleFailure(payment, "Bank Declined: " + response.reasonCode(), response.reasonCode());
        }
    }
}