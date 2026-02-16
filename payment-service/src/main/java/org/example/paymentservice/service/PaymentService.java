package org.example.paymentservice.service;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;

import java.util.UUID;

public interface PaymentService {

    void processPayment(UUID senderId, PaymentRequest request);

    void resumeProcessing(Payment payment);

}
