package com.openfashion.ledgerservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Database outbox record used for reliable event publication through Debezium.
 *
 * <p>After ledger persistence succeeds, result events are written here and later routed
 * from Postgres to Kafka topic {@code transaction.response}.
 */
@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Kafka message key / aggregate identifier used by Debezium event routing. */
    @Column(nullable = false)
    private String aggregateId;

    /** Ledger transaction type emitted as the outbox event type. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionType eventType;

    /** Serialized JSON payload delivered to downstream consumers. */
    @Column(columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;
}
