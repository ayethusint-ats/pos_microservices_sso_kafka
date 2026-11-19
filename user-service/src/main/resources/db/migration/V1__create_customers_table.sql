-- V1: initial schema for user-service.
-- Flyway records each applied script in flyway_schema_history. NEVER edit an applied
-- script (its checksum would change and startup fails). Add V2__..., V3__... instead.

CREATE TABLE customers (
    id                UUID         PRIMARY KEY,
    keycloak_user_id  VARCHAR(36)  NOT NULL,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(254) NOT NULL,
    phone_number      VARCHAR(20),
    -- Embedded Address value object
    street            VARCHAR(200) NOT NULL,
    city              VARCHAR(100) NOT NULL,
    postal_code       VARCHAR(20)  NOT NULL,
    country           VARCHAR(2)   NOT NULL,
    -- Optimistic locking and audit
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_customers_keycloak_user_id UNIQUE (keycloak_user_id),
    CONSTRAINT uk_customers_email UNIQUE (email),
    -- The app stores emails lower-case. The DB enforces it, so no other path can bypass the rule.
    CONSTRAINT ck_customers_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_customers_country_iso CHECK (country ~ '^[A-Z]{2}$')
);

-- The default sort of GET /api/customers. Without an index, every page would sort the whole table.
CREATE INDEX idx_customers_last_name ON customers (last_name);
