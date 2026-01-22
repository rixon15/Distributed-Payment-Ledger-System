package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String referenceId;

    @Column(nullable = false, length = 50)
    private TransactionType type;

    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    private Instant effectiveDate;

    @CreationTimestamp
    @Column(nullable = false)
    private Instant createdAt;
}
