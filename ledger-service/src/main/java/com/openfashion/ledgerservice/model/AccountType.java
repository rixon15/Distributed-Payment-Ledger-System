package com.openfashion.ledgerservice.model;

/**
 * High-level accounting classification for ledger accounts.
 */
public enum AccountType {
    /** Resource owned by the platform or user wallet. */
    ASSET,
    /** Obligation owed by the platform. */
    LIABILITY,
    /** Equity or balancing account such as system liquidity. */
    EQUITY,
    /** Revenue account. */
    INCOME,
    /** Cost/expense account. */
    EXPENSE
}
