package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.OutboxEvent;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {


    @Query(value = """
    SELECT * FROM outbox_events
    WHERE status = 'PENDING'
    ORDER BY created_at
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEvent> findTopForProcessing(@Param("limit") int limit);
}
