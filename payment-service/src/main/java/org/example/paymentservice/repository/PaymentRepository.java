package org.example.paymentservice.repository;

import jakarta.validation.constraints.NotBlank;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {


    Optional<Payment> findByIdempotencyKey(@NotBlank String s);

    List<Payment> findAllByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime dateTime);
}
