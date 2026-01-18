CREATE TABLE accounts
(
    id         UUID PRIMARY KEY,
    user_id    UUID,
    name       VARCHAR(255)   NOT NULL,
    type       VARCHAR(20)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,                 -- ISO 4217 (USD, EUR, etc.)
    balance    DECIMAL(19, 4) NOT NULL  DEFAULT 0.0000, -- 19 digits, 4 decimals for precision
    version    BIGINT         NOT NULL  DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_accounts_user_currency UNIQUE (user_id, currency)
);

CREATE INDEX idx_accounts_name ON accounts (name);
