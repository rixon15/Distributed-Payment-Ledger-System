package com.openfashion.ledgerservice.model;

public enum TransactionStatus {
    PENDING,
    POSTED,
    REJECTED_NSF, // Non-Sufficient Funds
    REJECTED_RISK, // Blocked by fraud rules
    REJECTED_INACTIVE, // Account is closed/frozebn
    FAILED,
    VOID
}
