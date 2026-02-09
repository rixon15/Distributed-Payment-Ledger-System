package org.example.paymentservice.service.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.repository.OutboxRepository;
import org.example.paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class InternalTransferStrategy extends PaymentStrategy {


    public InternalTransferStrategy(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                                    ObjectMapper objectMapper, TransactionTemplate tx, RestClient restClient) {
        super(paymentRepository, outboxRepository, objectMapper, tx, restClient);
    }

    @Override
    public boolean supports(TransactionType type) {
        //Grouping all "Ledger only" user initiated moves
        return type == TransactionType.TRANSFER ||
                type == TransactionType.PAYMENT ||
                type == TransactionType.REFUND;
    }

    @Override
    public void execute(Payment payment, PaymentRequest request) {
        log.info("Processing internal {} from {} to {}", payment.getType(), payment.getUserId(), payment.getReceiverId());

        finalizeStatus(payment, PaymentStatus.AUTHORIZED, null);
    }
}
