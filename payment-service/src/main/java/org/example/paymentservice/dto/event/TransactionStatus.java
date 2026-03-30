package org.example.paymentservice.dto.event;

/**
 * Status values used when payment-service emits ledger-facing outbox events.
 */
public enum TransactionStatus {
    /** Event accepted but not finalized on payment side. */
    PENDING,
    /** Funds reserved for multi-stage withdrawal flow. */
    RESERVED,
    /** Business operation is posted/authorized for settlement path. */
    POSTED,
    /** Operation failed and should be handled as unsuccessful. */
    FAILED,
    /** Reserved funds are released back to source account. */
    RELEASED
}

