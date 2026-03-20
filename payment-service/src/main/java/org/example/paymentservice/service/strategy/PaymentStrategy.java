package org.example.paymentservice.service.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.event.*;
import org.example.paymentservice.model.*;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public abstract class PaymentStrategy {

    protected final PaymentRepository paymentRepository;
    protected final OutboxRepository outboxRepository;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate tx;
    protected final RestClient restClient;


    public abstract boolean supports(PaymentType type);

    public abstract void execute(Payment payment, PaymentRequest request);

    protected void saveOutboxEvent(Payment payment, TransactionStatus status, String userMessage) {
        // Create Payload
        TransactionPayload payloadData = new TransactionPayload(
                payment.getUserId(),
                payment.getReceiverId(),
                payment.getAmount(),
                payment.getCurrency().toString(),
                status,
                userMessage,
                Instant.from(payment.getCreatedAt()),
                null
        );

        TransactionInitiatedEvent eventPayload = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                payment.getType(),
                payment.getId(),
                Instant.now(),
                payloadData
        );

        try {
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateId(payment.getUserId().toString())
                    .eventType(payment.getType())
                    .payload(jsonPayload)
                    .status(OutboxStatus.PROCESSED)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to queue outbox event for payment {}", payment.getId(), e);
            throw new SerializationFailedException("Failed to serialize event", e);
        }
    }

    protected void handleFailure(Payment payment, String internalReason, String userMessage) {
        log.warn("Payment {} failed: {}", payment.getId(), internalReason);

        tx.executeWithoutResult(_ -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage(internalReason);
            paymentRepository.save(payment);

            saveOutboxEvent(payment, TransactionStatus.FAILED, userMessage);
        });
    }

    protected void finalizeStatus(Payment payment, PaymentStatus status, UUID externalId) {
        tx.executeWithoutResult(_ -> {
            payment.setStatus(status);
            payment.setExternalTransactionId(externalId != null ? externalId.toString() : null);
            paymentRepository.save(payment);

            TransactionStatus ledgerStatus = switch (status) {
                case AUTHORIZED -> TransactionStatus.SUCCESS;
                case FAILED -> TransactionStatus.FAILED;
                default -> TransactionStatus.PENDING;
            };

            saveOutboxEvent(payment, ledgerStatus, null);
        });
    }

    protected void reconcileWithBank(Payment payment, BankPaymentResponse bankResult, String bankUrl, int retryCount) {
        if (bankResult == null) return;

        switch (bankResult.status()) {
            case APPROVED -> {
                log.info("Bank Status SUCCESS: Finalizing payment {} as AUTHORIZED", payment.getId());
                finalizeStatus(payment, PaymentStatus.AUTHORIZED, bankResult.transactionId());
            }
            case DECLINED -> {
                log.warn("Bank Status DECLINED: Failing payment {}", payment.getId());
                handleFailure(payment, "Bank Declined", bankResult.reasonCode());
            }
            case PENDING ->
                    log.info("Bank Status PENDING: No action taken for {}. Will retry inquiry.", payment.getId());
            case NOT_FOUND -> {

                if (retryCount > 3) {
                    log.error("Max retries reached for payment {}. Bank stuck in NOT_FOUND.", payment.getId());
                    handleFailure(payment, "Bank Unavailable", "Max retries exceeded");
                    return;
                }

                log.info("Bank Status NOT_FOUND: Proceeding with fresh execution for {}", payment.getId());
                // This is the only state where we actually call the POST /pay endpoint
                executeNewPayment(payment, bankUrl, retryCount + 1);
            }

        }
    }

    protected void reconcileWithBank(Payment payment, BankPaymentResponse bankResult, String bankUrl) {
        reconcileWithBank(payment, bankResult, bankUrl, 0);
    }

    private void executeNewPayment(Payment payment, String bankUrl, int retryCount) {
        BankPaymentRequest bankRequest = new BankPaymentRequest(
                payment.getId(),
                "EXT-ACCT-" + payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency().getCode()
        );

        try {
            BankPaymentResponse response = restClient.post()
                    .uri(bankUrl + "/pay")
                    .body(bankRequest)
                    .retrieve()
                    .body(BankPaymentResponse.class);

            // After the POST, we use the same reconciliation logic
            reconcileWithBank(payment, response, bankUrl, retryCount);
        } catch (Exception _) {
            log.error("POST /pay failed for payment {}. Recovery scheduler will inquire status later.", payment.getId());
        }
    }
}
