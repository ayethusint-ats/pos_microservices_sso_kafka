-- V1: initial schema for payment-service (database payment_db, role payment_svc).
-- No balances here: account-service owns money. This table records transfer requests and outcomes.

CREATE TABLE payments (
    id                 UUID           PRIMARY KEY,
    payer_id           VARCHAR(36)    NOT NULL,   -- Keycloak "sub" of the customer
    source_account_id  UUID           NOT NULL,   -- account-service IDs: no FK across databases
    target_account_id  UUID           NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL,
    currency           VARCHAR(3),                -- set once account-service confirmed it
    description        VARCHAR(140),
    status             VARCHAR(30)    NOT NULL,
    failure_code       VARCHAR(50),
    failure_reason     VARCHAR(500),
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_different_accounts CHECK (source_account_id <> target_account_id),
    CONSTRAINT ck_payments_currency_iso CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING', 'DEBITED', 'COMPLETED', 'REJECTED', 'FAILED', 'REVERSED', 'NEEDS_RECONCILIATION'))
);

-- "My payments, newest first".
CREATE INDEX idx_payments_payer ON payments (payer_id, created_at DESC);
-- Back-office work queue: "show me everything that NEEDS_RECONCILIATION / is stuck in DEBITED".
CREATE INDEX idx_payments_status ON payments (status, created_at);
