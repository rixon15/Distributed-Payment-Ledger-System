package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;


/**
 * Repository for ledger outbox events.
 *
 * <p>Rows written here are picked up from the database by Debezium and published
 * to Kafka topic {@code transaction.response}.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

}
