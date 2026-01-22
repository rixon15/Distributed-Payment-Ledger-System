CREATE TABLE accounts
(
    id         UUID           NOT NULL,
    user_id    UUID           NOT NULL,
    name       VARCHAR(255)   NOT NULL,
    type       VARCHAR(20)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    balance    DECIMAL(19, 4) NOT NULL,
    version    BIGINT         NOT NULL,
    status     varchar(20)    NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
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
    id             UUID         NOT NULL,
    reference_id   VARCHAR(255) NOT NULL,
    type           SMALLINT     NOT NULL,
    status         SMALLINT     NOT NULL,
    metadata       TEXT,
    effective_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id)
);

ALTER TABLE accounts
    ADD CONSTRAINT uc_d261849cf878d3fc1b546e29a UNIQUE (user_id, currency);

ALTER TABLE transactions
    ADD CONSTRAINT uc_transactions_referenceid UNIQUE (reference_id);

ALTER TABLE postings
    ADD CONSTRAINT FK_POSTINGS_ON_ACCOUNT FOREIGN KEY (account_id) REFERENCES accounts (id);

CREATE INDEX idx_postings_account_id ON postings (account_id);

ALTER TABLE postings
    ADD CONSTRAINT FK_POSTINGS_ON_TRANSACTION FOREIGN KEY (transaction_id) REFERENCES transactions (id);

CREATE INDEX idx_postings_transaction_id ON postings (transaction_id);