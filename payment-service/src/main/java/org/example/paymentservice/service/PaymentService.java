package org.example.paymentservice.service;

import org.example.paymentservice.dto.PaymentRequest;

import java.util.List;
import java.util.UUID;

public interface PaymentService {

    void processPayment(UUID senderId, PaymentRequest request);

    void resumeProcessing(UUID paymentId);

    List<UUID> claimStuckPayments(int batchSize);
}
