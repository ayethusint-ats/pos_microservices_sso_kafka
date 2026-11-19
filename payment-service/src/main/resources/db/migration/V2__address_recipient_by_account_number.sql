-- V2 (Phase 10): customers address the recipient by ACCOUNT NUMBER, which may belong to another customer.
-- The target's internal ID is only known after account-service resolved the number.

ALTER TABLE payments ADD COLUMN target_account_number VARCHAR(10);
ALTER TABLE payments ALTER COLUMN target_account_id DROP NOT NULL;

-- Phase 9 rows have an ID but no number, and we can't backfill numbers: they live in account_db,
-- another service's database. So the rule is "at least one of them", not "number NOT NULL".
ALTER TABLE payments ADD CONSTRAINT ck_payments_target_known
    CHECK (target_account_id IS NOT NULL OR target_account_number IS NOT NULL);
ALTER TABLE payments ADD CONSTRAINT ck_payments_target_number_digits
    CHECK (target_account_number IS NULL OR target_account_number ~ '^[0-9]{10}$');
