package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.SagaActionResult;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@Slf4j
public class DepositStrategy implements PaymentStrategy {

    private final RestClient restClient;
    private final String bankUrl;

    public DepositStrategy(RestClient restClient, @Value("${app.bank.url}") String bankUrl) {
        this.restClient = restClient;
        this.bankUrl = bankUrl;
    }

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.DEPOSIT;
    }

    @Override
    public SagaActionResult performAction(UUID senderId, PaymentRequest request) {
        log.info("Initiating Deposit for user: {}", senderId);

        BankPaymentRequest bankRequest = new BankPaymentRequest(
                UUID.randomUUID(), //Unique ref for the bank
                "src_card_" + senderId, // Mocked source account token
                request.amount(),
                request.currency()
        );


        try {
            BankPaymentResponse response = restClient.post()
                    .uri(bankUrl + "/pay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bankRequest)
                    .retrieve()
                    .body(BankPaymentResponse.class);

            if (response == null) {
                throw new IllegalStateException("Bank returned empty response");
            }

            if ("APPROVED".equalsIgnoreCase(response.status().toString())) {
                log.info("Bank approved the deposit. External ID: {}", response.transactionId());

                return SagaActionResult.success(response.transactionId());
            } else {
                log.warn("Bank DECLINED deposit: {}", response.reasonCode());
                return SagaActionResult.failure("Bank Declined", response.reasonCode());
            }

        } catch (Exception e) {
            log.error("Technical failure communicating with the bank for user: {}", senderId, e);
            return SagaActionResult.failure("Bank unavailable", e.getMessage());
        }
    }
}
