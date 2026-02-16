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

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {


    Optional<Payment> findByIdempotencyKey(@NotBlank String s);

    @Query(value = """
            SELECT * FROM payments
            WHERE status = 'PENDING'
            AND created_at < :threshold
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Payment> findStuckPaymentsForRecovery(@Param("threshold") LocalDateTime threshold);
}
