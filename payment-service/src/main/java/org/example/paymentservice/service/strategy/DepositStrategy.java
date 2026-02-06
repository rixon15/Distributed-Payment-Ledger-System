package org.example.paymentservice.service.strategy;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.SagaActionResult;
import org.example.paymentservice.model.TransactionType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DepositStrategy implements PaymentStrategy{

    @Override
    public boolean supports(TransactionType type) {
        return type == TransactionType.DEPOSIT;
    }

    @Override
    public SagaActionResult performAction(UUID senderId, PaymentRequest request) {
        return null;
    }
}
