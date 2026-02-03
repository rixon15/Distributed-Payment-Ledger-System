CREATE TABLE accounts
(
    id         UUID           NOT NULL,
    user_id    UUID           NOT NULL,
    name       VARCHAR(255)   NOT NULL,
    type       VARCHAR(20)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    balance    DECIMAL(19, 4) NOT NULL DEFAULT 0.0000,
    version    BIGINT         NOT NULL DEFAULT 0,
    status     varchar(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_accounts PRIMARY KEY (id)
);

CREATE TABLE postings
(
    id             UUID           NOT NULL,
    transaction_id UUID           NOT NULL,
    account_id     UUID           NOT NULL,
    amount         DECIMAL(19, 4) NOT NULL,
    direction      VARCHAR(10)    NOT NULL,
    CONSTRAINT pk_postings PRIMARY KEY (id)
);

CREATE TABLE transactions
(
    id             UUID                     NOT NULL,
    reference_id   VARCHAR(255)             NOT NULL,
    type           VARCHAR(20)              NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    metadata       TEXT,
    effective_date TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_transactions PRIMARY KEY (id)
);

CREATE TABLE outbox_events
(
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status       VARCHAR(20)              DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_status ON outbox_events (status, created_at);

ALTER TABLE accounts
    ADD CONSTRAINT uc_accounts_user_id_currency UNIQUE (user_id, currency);

ALTER TABLE transactions
    ADD CONSTRAINT uc_transactions_reference_id UNIQUE (reference_id);

ALTER TABLE postings
    ADD CONSTRAINT FK_POSTINGS_ON_ACCOUNT FOREIGN KEY (account_id) REFERENCES accounts (id);

CREATE INDEX idx_postings_account_id ON postings (account_id);

ALTER TABLE postings
    ADD CONSTRAINT FK_POSTINGS_ON_TRANSACTION FOREIGN KEY (transaction_id) REFERENCES transactions (id);

CREATE INDEX idx_postings_transaction_id ON postings (transaction_id);

CREATE INDEX idx_accounts_name ON accounts (name);

CREATE INDEX idx_transaction_status ON transactions (status);