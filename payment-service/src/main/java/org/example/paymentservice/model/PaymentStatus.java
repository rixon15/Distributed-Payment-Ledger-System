package org.example.paymentservice.model;

/**
 * Lifecycle status of a payment record inside payment-service.
 */
public enum PaymentStatus {
    /** Initial or in-progress state awaiting downstream completion. */
    PENDING,
    /** Authorized by payment-side checks and/or bank confirmation. */
    AUTHORIZED,
    /** Final failure state. */
    FAILED,
    /** Fully completed end state. */
    COMPLETED,
    /** Temporarily claimed by recovery worker. */
    RECOVERING,
    /** Completed as a refund operation. */
    REFUNDED,
    /** Requires manual handling after risk screening. */
    MANUAL_REVIEW
}

