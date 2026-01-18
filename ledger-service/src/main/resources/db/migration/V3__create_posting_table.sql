CREATE TABLE postings (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    direction VARCHAR(10) NOT NULL,

    CONSTRAINT fk_postings_transaction
                      FOREIGN KEY (transaction_id)
                      REFERENCES transactions(id),

    CONSTRAINT fk_postings_account
                      FOREIGN KEY (account_id)
                      REFERENCES accounts(id)
);

CREATE INDEX idx_postings_account_id ON postings(account_id);
CREATE INDEX idx_postings_transaction_id ON postings(transaction_id);