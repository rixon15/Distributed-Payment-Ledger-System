package com.openfashion.ledgerservice.model;

public enum TransactionType {

    /**
     * EXTERNAL DEPOSIT (Money entering the system)
     * Logic: Debit WORLD_LIQUIDITY (Equity) -> Credit USER (Asset)
     * Ex: User loads wallet via Credit Card.
     */
    DEPOSIT,

    /**
     * EXTERNAL WITHDRAWAL (Money leaving the system)
     * Logic: Debit USER (Asset) -> Credit WORLD_LIQUIDITY (Equity)
     * Ex: User sends money to their real bank account.
     */
    WITHDRAWAL,

    /**
     * INTERNAL TRANSFER (P2P)
     * Logic: Debit USER_A (Asset) -> Credit USER_B (Asset)
     * Ex: Alice sends money to Bob.
     */
    TRANSFER,

    /**
     * PAYMENT (Purchase)
     * Logic: Debit USER (Asset) -> Credit MERCHANT (Asset)
     * Ex: User buys a coffee.
     */
    PAYMENT,

    /**
     * REFUND
     * Logic: Debit MERCHANT (Asset) -> Credit USER (Asset)
     * Ex: User returns the coffee.
     */
    REFUND,

    /**
     * PLATFORM FEE
     * Logic: Debit USER (Asset) -> Credit REVENUE_ACCOUNT (Income)
     * Ex: We charge $0.50 for the transfer.
     */
    FEE,

    /**
     * ADMINISTRATIVE ADJUSTMENT
     * Logic: Debit/Credit Admin Ops Account -> Credit/Debit User
     * Ex: Customer support fixes a mistake manually.
     */
    ADJUSTMENT,

    /**
     * INTEREST PAYOUT
     * Logic: Debit INTEREST_EXPENSE (Expense) -> Credit USER (Asset)
     * Ex: Paying users 2% yield on their balance.
     */
    INTEREST

}
