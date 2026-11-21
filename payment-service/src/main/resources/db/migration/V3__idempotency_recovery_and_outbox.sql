-- V3 (Phase 13): Idempotency-Key, saga recovery, transactional outbox.

-- 1. Idempotency-Key: one payment per (customer, key). A retried request finds the payment it created.
--    request_hash detects the same key reused for a DIFFERENT request (a client bug, refused).
ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE payments ADD COLUMN request_hash VARCHAR(64);
-- NULLs are distinct in a UNIQUE constraint: payments without a key don't collide.
ALTER TABLE payments ADD CONSTRAINT uk_payments_payer_idempotency_key UNIQUE (payer_id, idempotency_key);

-- 2. Saga: COMPENSATING = the credit was refused, the refund is in progress.
ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status CHECK (status IN
    ('PENDING', 'DEBITED', 'COMPENSATING', 'COMPLETED', 'REJECTED', 'FAILED', 'REVERSED', 'NEEDS_RECONCILIATION'));

-- 3. Recovery: when the job may next look at an unfinished payment, and how often it already tried.
--    NULL = nothing to recover (final). Rows from earlier phases stay NULL on purpose: their debits
--    weren't recorded in account-service's idempotency ledger, so automatic recovery can't judge them.
ALTER TABLE payments ADD COLUMN next_recovery_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;
CREATE INDEX idx_payments_recovery_due ON payments (next_recovery_at) WHERE next_recovery_at IS NOT NULL;

-- 4. Transactional outbox (same design as account-service).
CREATE TABLE outbox_events (
    id              UUID           PRIMARY KEY,
    seq             BIGINT         GENERATED ALWAYS AS IDENTITY,
    topic           VARCHAR(100)   NOT NULL,
    message_key     VARCHAR(100)   NOT NULL,
    payload         TEXT           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT            NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),

    CONSTRAINT uk_outbox_events_seq UNIQUE (seq)
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
