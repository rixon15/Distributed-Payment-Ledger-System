package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable double-entry line item belonging to a ledger transaction.
 *
 * <p>Each business transaction is represented by postings that debit one account
 * and credit another account for the same amount.
 */
@Entity
@Table(name = "postings", indexes = {
        @Index(name = "idx_postings_account_id", columnList = "account_id"),
        @Index(name = "idx_postings_transaction_id", columnList = "transaction_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Posting {

    /**
     * Convenience constructor for building a posting line once transaction/account resolution is complete.
     */
    public Posting(Transaction transaction, Account account, BigDecimal amount, PostingDirection direction) {
        this.transaction = transaction;
        this.account = account;
        this.amount = amount;
        this.direction = direction;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Parent business transaction for this posting line. */
    @ManyToOne
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** Account affected by this posting. */
    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    /** Debit or credit side of the accounting pair. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PostingDirection direction;

}
