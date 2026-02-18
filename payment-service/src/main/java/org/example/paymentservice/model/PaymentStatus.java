package org.example.paymentservice.model;

public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    FAILED,
    COMPLETED,
    RECOVERING,
    REFUNDED,
    MANUAL_REVIEW
}
