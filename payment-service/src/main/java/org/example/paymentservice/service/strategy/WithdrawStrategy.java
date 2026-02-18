package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.ReleaseRequest;
import org.example.paymentservice.dto.ReservationRequest;
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

import java.util.UUID;

@Component
@Slf4j
public class WithdrawStrategy extends PaymentStrategy {

    private final String ledgerUrl;
    private final String bankUrl;

    public WithdrawStrategy(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                            ObjectMapper objectMapper, TransactionTemplate tx, RestClient restClient,
                            @Value("${app.ledger.url}") String ledgerUrl,
                            @Value("${app.bank.url}") String bankUrl) {
        super(paymentRepository, outboxRepository, objectMapper, tx, restClient);
        this.ledgerUrl = ledgerUrl;
        this.bankUrl = bankUrl;
    }

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.WITHDRAWAL;
    }

    @Override
    public void execute(Payment payment, PaymentRequest request) {

        try {
            ReservationRequest reservReq = new ReservationRequest(
                    payment.getUserId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    payment.getId()
            );

            restClient.post()
                    .uri(ledgerUrl + "/accounts/reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reservReq)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            handleFailure(payment, "Ledger Reservation Failed: " + e.getMessage(), e.getMessage());
            return;
        }

        if (payment.getExternalTransactionId() != null) {
            log.info("Payment {} already has external ID. Skipping bank call.", payment.getId());
            finalizeStatus(payment, PaymentStatus.AUTHORIZED, UUID.fromString(payment.getExternalTransactionId()));
            return;
        }

        BankPaymentRequest bankRequest = new BankPaymentRequest(
                payment.getId(),
                "EXT-ACCT-" + payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency().getCode()
        );

        BankPaymentResponse bankResult;
        try {
            bankResult = restClient.post()
                    .uri(bankUrl + "/pay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bankRequest)
                    .retrieve()
                    .body(BankPaymentResponse.class);
        } catch (Exception e) {
            // Network error? The Recovery Scheduler will pick this up and retry.
            // We do NOT release the reservation yet, because we don't know if the money moved.
            throw new IllegalStateException("Bank call failed, will retry", e);
        }

        if (bankResult == null) {
            throw new IllegalStateException("Failed to post to bank api");
        }

        if (bankResult.status().equals(BankPaymentStatus.APPROVED)) {
            finalizeStatus(payment, PaymentStatus.AUTHORIZED, bankResult.transactionId());
        } else {
            rollbackReservation(payment);

            handleFailure(payment, "Bank Declined: " + bankResult.reasonCode(), bankResult.reasonCode());
        }
    }

    private void rollbackReservation(Payment payment) {
        try {
            restClient.post()
                    .uri(ledgerUrl + "/accounts/release-reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReleaseRequest(payment.getId()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("CRITICAL: Failed to release reserve for payment {}", payment.getId(), e);
            payment.setStatus(PaymentStatus.MANUAL_REVIEW);
            paymentRepository.save(payment);
        }
    }
}
