package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "currency"}) // Optional: Mirrors DB constraint for documentation
})
@Data
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AccountType type; //Asset, Liability, equity, etc

    @Column(nullable = false, length = 3)
    @Enumerated(EnumType.STRING)
    private CurrencyType currency; //ISO string (USD RON)


    @Column(nullable = false, precision = 19, scale = 4)
    @Digits(integer = 15, fraction = 4, message = "Amount exceeds 15 integer digits or 4 decimal places")
    private BigDecimal balance = BigDecimal.ZERO;

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
