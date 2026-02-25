package org.example.paymentservice.repository;

import org.example.paymentservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {


    @Query(value = """
                    select * from outbox_events
                    where status = 'PENDING'
                    order by created_at
                    limit 50
                    for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> findTop50ForProcessing();
}
