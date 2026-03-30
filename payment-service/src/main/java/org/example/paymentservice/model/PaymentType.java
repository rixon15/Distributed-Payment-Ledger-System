package org.example.paymentservice.model;

/**
 * Supported payment business operation types initiated via payment-service.
 */
public enum PaymentType {
    /** External top-up flow entering the system. */
    DEPOSIT,
    /** External cash-out flow leaving the system. */
    WITHDRAWAL,
    /** Internal user-to-user transfer. */
    TRANSFER,
    /** Internal payment flow (e.g. user to merchant). */
    PAYMENT,
    /** Reverse movement for prior payment/transfer. */
    REFUND,
}

