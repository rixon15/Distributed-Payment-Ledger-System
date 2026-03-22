package org.example.paymentservice.repository;

import org.example.paymentservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

}
