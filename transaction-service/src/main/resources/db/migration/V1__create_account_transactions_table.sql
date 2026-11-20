-- V1: transaction-service's read model (database transaction_db, role transaction_svc).
-- Every row comes from an account-service event; the table can be rebuilt by replaying the topic.

CREATE TABLE account_transactions (
    id              UUID           PRIMARY KEY,   -- = event ID: a duplicate delivery can't insert twice
    account_id      UUID           NOT NULL,
    account_number  VARCHAR(10)    NOT NULL,
    owner_id        VARCHAR(36)    NOT NULL,      -- Keycloak "sub": "my statement" needs no other service
    kind            VARCHAR(20)    NOT NULL,
    direction       VARCHAR(6)     NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    balance_after   NUMERIC(19, 2) NOT NULL,
    payment_id      UUID,
    occurred_at     TIMESTAMPTZ    NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_tx_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_tx_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_tx_owner ON account_transactions (owner_id, occurred_at DESC);
CREATE INDEX idx_tx_account ON account_transactions (account_id, occurred_at DESC);
