package com.openfashion.ledgerservice.model;

/**
 * Processing state of a ledger transaction or event payload.
 */
public enum TransactionStatus {
    /** Accepted but not yet fully finalized. */
    PENDING,
    /** Successfully posted to the ledger. */
    POSTED,
    /** Rejected due to insufficient funds. */
    REJECTED_NSF,
    /** Rejected by upstream risk/fraud logic. */
    REJECTED_RISK,
    /** Rejected because one of the accounts is not active. */
    REJECTED_INACTIVE,
    /** Rejected because of faulty business logic */
    REJECTED_VALIDATION,
    /** Failed due to processing or integration error. */
    FAILED,
    /** Neutralized / voided after prior creation. */
    VOID
}
