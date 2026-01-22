package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PostingDirection direction;

}
