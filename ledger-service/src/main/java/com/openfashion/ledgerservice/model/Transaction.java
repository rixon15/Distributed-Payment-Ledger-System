package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;


/**
 * Immutable business transaction journal entry.
 *
 * <p>A transaction captures the high-level accounting event identified by a business
 * reference id and concrete transaction type. The detailed debit/credit math is stored
 * separately in associated {@link Posting} rows.
 */
@Entity
@Data
@Builder
@Table(name = "transactions")
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Upstream business reference shared across related ledger processing stages. */
    @Column(nullable = false, unique = true)
    private UUID referenceId;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private TransactionType type;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    /** Serialized request/event context retained for audit and debugging. */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    /** Effective accounting timestamp for the journal entry. */
    @Column(nullable = false)
    private Instant effectiveDate;

    @Column(nullable = false)
    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false)
    private Instant createdAt;
}
