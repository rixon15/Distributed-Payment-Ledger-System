package org.example.paymentservice.service.strategy;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.SagaActionResult;
import org.example.paymentservice.model.TransactionType;

import java.util.UUID;

public interface PaymentStrategy {

    boolean supports(TransactionType type);

    SagaActionResult performAction(UUID senderId, PaymentRequest request);

}
