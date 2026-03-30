package com.openfashion.ledgerservice.model;

/**
 * Operational state of a ledger account.
 */
public enum AccountStatus {
    /** Account is usable for normal processing. */
    ACTIVE,
    /** Account exists but should not accept normal activity. */
    FROZEN,
    /** Account is no longer active for processing. */
    CLOSED
}
