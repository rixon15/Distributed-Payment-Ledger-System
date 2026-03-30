package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.PaymentType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Strategy for ledger-only internal movements (TRANSFER, PAYMENT, REFUND).
 *
 * <p>No external bank call is needed; payment is authorized and published to outbox.
 */
@Component
@Slf4j
public class InternalTransferStrategy extends PaymentStrategy {


    public InternalTransferStrategy(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                                    ObjectMapper objectMapper, TransactionTemplate tx, RestClient restClient) {
        super(paymentRepository, outboxRepository, objectMapper, tx, restClient);
    }

    /**
     * Supports internal payment types handled fully by ledger.
     */
    @Override
    public boolean supports(PaymentType type) {
        //Grouping all "Ledger only" user initiated moves
        return type == PaymentType.TRANSFER ||
                type == PaymentType.PAYMENT ||
                type == PaymentType.REFUND;
    }

    /**
     * Finalizes internal movement as AUTHORIZED and emits outbox event.
     */
    @Override
    public void execute(Payment payment, PaymentRequest request) {
        log.info("Processing internal {} from {} to {}", payment.getType(), payment.getUserId(), payment.getReceiverId());

        finalizeStatus(payment, PaymentStatus.AUTHORIZED, null);
    }
}
