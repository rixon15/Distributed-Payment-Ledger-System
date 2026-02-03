package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.dto.OutboxEvent;
import com.openfashion.ledgerservice.dto.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

}
