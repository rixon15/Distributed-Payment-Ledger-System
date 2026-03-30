package org.example.paymentservice.model;

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
 * Payment outbox row used for reliable event publication through Debezium.
 *
 * <p>Each record is routed to Kafka and consumed by ledger-service.
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

    /** Aggregate key used as event partition key and routing context. */
    @Column(nullable = false)
    private String aggregateId;

    /** Payment type emitted as outbox event type. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentType eventType;

    /** Serialized JSON payload delivered downstream. */
    @Column(columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;
}
