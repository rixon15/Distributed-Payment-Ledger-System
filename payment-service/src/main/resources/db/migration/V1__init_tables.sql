CREATE TABLE payments
(
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    idempotency_key         VARCHAR(255)   NOT NULL,
    amount                  DECIMAL(19, 4) NOT NULL,
    status                  VARCHAR(255)   NOT NULL,
    external_transaction_id VARCHAR(255),
    error_message           VARCHAR(255),
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    update_at               TIMESTAMP WITHOUT TIME ZONE,
    version                 BIGINT,
    CONSTRAINT pk_payments PRIMARY KEY (id)
);

ALTER TABLE payments
    ADD CONSTRAINT uc_payments_idempotencykey UNIQUE (idempotency_key);

CREATE UNIQUE INDEX idx_idempotency ON payments (idempotency_key);

CREATE INDEX idx_payment_status ON payments (status);

CREATE INDEX idx_payment_user ON payments (user_id);