-- V2 (Phase 13): idempotent payment operations + transactional outbox.

-- 1. Which payment already moved money here. One row per (payment, leg):
--      DEBIT       the source was debited (TRANSFER_OUT), or the debit was VOIDed (fenced off)
--      SETTLEMENT  the target was credited (TRANSFER_IN) OR the source was refunded (REFUND), never both
--    The primary key is the idempotency guarantee: a second debit/credit for the same payment can't
--    insert a row, even when two requests race (the loser's transaction fails and rolls back).
--    No FK to accounts: the ledger is append-only history and VOID rows have no account.
CREATE TABLE payment_operations (
    payment_id      UUID           NOT NULL,
    leg             VARCHAR(20)    NOT NULL,
    kind            VARCHAR(20)    NOT NULL,
    account_id      UUID,
    amount          NUMERIC(19, 2),
    created_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_payment_operations PRIMARY KEY (payment_id, leg),
    CONSTRAINT ck_payment_operations_leg CHECK (leg IN ('DEBIT', 'SETTLEMENT')),
    CONSTRAINT ck_payment_operations_kind CHECK (
        (leg = 'DEBIT' AND kind IN ('TRANSFER_OUT', 'VOID')) OR
        (leg = 'SETTLEMENT' AND kind IN ('TRANSFER_IN', 'REFUND'))),
    CONSTRAINT ck_payment_operations_money CHECK (
        kind = 'VOID' OR (account_id IS NOT NULL AND amount > 0))
);

-- 2. Transactional outbox: events are INSERTed in the same transaction as the balance change,
--    and OutboxRelay sends them to Kafka afterwards. Either both are committed or neither.
CREATE TABLE outbox_events (
    id              UUID           PRIMARY KEY,             -- = the event's eventId
    seq             BIGINT         GENERATED ALWAYS AS IDENTITY,   -- publication order
    topic           VARCHAR(100)   NOT NULL,
    message_key     VARCHAR(100)   NOT NULL,
    payload         TEXT           NOT NULL,                -- the JSON exactly as consumers receive it
    created_at      TIMESTAMPTZ    NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT            NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),

    CONSTRAINT uk_outbox_events_seq UNIQUE (seq)
);

-- The relay's query: "unpublished, oldest first". A partial index stays tiny: it only contains the backlog.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
-- Housekeeping deletes published rows after the retention period.
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
