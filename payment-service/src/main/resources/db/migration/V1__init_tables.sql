CREATE TABLE outbox_events
(
    id           UUID                     PRIMARY KEY,
    aggregate_id VARCHAR(255)             NOT NULL,
    event_type   VARCHAR(255)             NOT NULL,
    payload      JSONB                    NOT NULL,
    status       VARCHAR(50)              NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE payments
(
    id                      UUID                        PRIMARY KEY,
    user_id                 UUID                        NOT NULL,
    receiver_id             UUID,
    type                    VARCHAR(255)                NOT NULL,
    idempotency_key         VARCHAR(255)                NOT NULL,
    amount                  DECIMAL(19, 4)              NOT NULL,
    currency                VARCHAR(3)                  NOT NULL,
    status                  VARCHAR(255)                NOT NULL,
    external_transaction_id VARCHAR(255),
    error_message           VARCHAR(255),
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE,
    version                 BIGINT
);

ALTER TABLE payments ADD CONSTRAINT uc_payments_idempotencykey UNIQUE (idempotency_key);

CREATE INDEX idx_idempotency ON payments (idempotency_key);
CREATE INDEX idx_payment_status ON payments (status);
CREATE INDEX idx_payment_user ON payments (user_id);
CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_outbox_events_aggregate_id ON outbox_events (aggregate_id);