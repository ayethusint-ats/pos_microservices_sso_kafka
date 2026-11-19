-- V1: initial schema for account-service.
-- The database is the last line of defense: even a buggy code path or a manual SQL
-- session can't create a negative balance or an unknown status.

CREATE TABLE accounts (
    id              UUID           PRIMARY KEY,
    account_number  VARCHAR(10)    NOT NULL,
    owner_id        VARCHAR(36)    NOT NULL,   -- Keycloak "sub". No FK: users live in another service.
    type            VARCHAR(20)    NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    balance         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT ck_accounts_type CHECK (type IN ('CHECKING', 'SAVINGS')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_accounts_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_accounts_number_digits CHECK (account_number ~ '^[0-9]{10}$')
);

-- "List my accounts" filters by owner_id and sorts by created_at.
CREATE INDEX idx_accounts_owner_id ON accounts (owner_id, created_at);
