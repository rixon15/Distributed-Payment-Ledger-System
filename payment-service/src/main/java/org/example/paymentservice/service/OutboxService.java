package org.example.paymentservice.service;

import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.Payment;

public interface OutboxService {

    public void saveOutboxEvent(Payment payment, TransactionStatus status, String userMessage);

}
