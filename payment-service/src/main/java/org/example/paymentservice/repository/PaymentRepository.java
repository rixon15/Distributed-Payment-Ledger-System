package org.example.paymentservice.repository;

import jakarta.validation.constraints.NotBlank;
import org.example.paymentservice.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for persisted payment lifecycle records.
 *
 * <p>Supports idempotency checks and recovery selection for stale pending payments.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Finds an existing payment by idempotency key.
     *
     * @param s client-supplied idempotency key
     * @return existing payment if already created
     */
    Optional<Payment> findByIdempotencyKey(@NotBlank String s);

    /**
     * Claims stale pending payments for recovery processing.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} semantics to reduce worker contention.
     *
     * @param threshold upper bound for stale {@code updated_at}
     * @param limit max number of rows to claim
     * @return recoverable pending payments
     */
    @Query(value = """
        SELECT * FROM payments
        WHERE status = 'PENDING'
        AND updated_at < :threshold
        ORDER BY updated_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Payment> findStuckPaymentsForRecovery(
            @Param("threshold") LocalDateTime threshold,
            @Param("limit") int limit
    );
}
