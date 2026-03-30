package org.example.paymentservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;


/**
 * Persistent payment lifecycle aggregate for API-driven payment requests.
 *
 * <p>Tracks idempotency, execution status, external bank correlation id, and
 * recovery-related state transitions.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_user", columnList = "user_id"),
        @Index(name = "idx_payment_status", columnList = "status"),
        @Index(name = "idx_idempotency", columnList = "idempotency_key", unique = true)
        // Note: Idempotency Key should be unique at the DB level for safety

})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Initiating user of the payment request. */
    @Column(nullable = false)
    private UUID userId;

    /** Destination user for transfer/payment/refund flows; null for some external flows. */
    private UUID receiverId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentType type;

    /** Client-provided deduplication key; enforced unique in DB. */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CurrencyType currency;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /** External bank transaction correlation id, if available. */
    private String externalTransactionId;

    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Optimistic version used during concurrent updates. */
    @Version
    private Long version;

    /**
     * Reconstructs a request-like object from persisted payment state.
     *
     * <p>Used by recovery flow to resume business execution.
     */
    public PaymentRequest mapToRequest() {
        return new PaymentRequest(
                this.receiverId,
                this.idempotencyKey,
                this.type,
                this.amount,
                this.currency.toString()
        );
    }
}