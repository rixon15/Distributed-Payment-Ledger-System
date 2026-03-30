package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;


/**
 * Ledger account snapshot storing the current balance for a user or system bucket.
 *
 * <p>This entity represents the mutable balance view used for reads and strategy resolution.
 * The immutable audit trail is kept separately in {@link Transaction} and {@link Posting}.
 */
@Entity
@Table(name = "accounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "currency"}) // Optional: Mirrors DB constraint for documentation
})
@Data
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Owning user for normal wallet accounts; system accounts also reuse this table structure. */
    @Column(nullable = false)
    private UUID userId;

    /** Logical account name, also used to resolve named system accounts. */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AccountType type; //Asset, Liability, equity, etc

    @Column(nullable = false, length = 3)
    @Enumerated(EnumType.STRING)
    private CurrencyType currency; //ISO string (USD RON)


    /** Snapshot balance; immutable movement history is recorded in postings. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Digits(integer = 15, fraction = 4, message = "Amount exceeds 15 integer digits or 4 decimal places")
    private BigDecimal balance = BigDecimal.ZERO;

    /** Optimistic version for concurrent snapshot updates. */
    @Column(nullable = false)
    @Version
    private long version;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AccountStatus status = AccountStatus.ACTIVE;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
