-- Fund reservations (holds) and idempotent transfer credits used by transfer-service.
-- Additive migration: existing rows and APIs keep working.

-- available = balance - reserved_balance. Withdrawals and new holds use "available".
ALTER TABLE accounts ADD COLUMN reserved_balance NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_reserved
    CHECK (reserved_balance >= 0 AND reserved_balance <= balance);

CREATE TABLE account_holds (
    id          UUID           PRIMARY KEY,
    reference   VARCHAR(64)    NOT NULL,       -- idempotency key, e.g. the transfer reference
    account_id  UUID           NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    status      VARCHAR(16)    NOT NULL,
    -- A tombstone is a RELEASED marker written when a release arrives before (or instead of)
    -- the reserve. It guarantees that a delayed reserve with the same reference is refused.
    tombstone   BOOLEAN        NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_holds_reference UNIQUE (reference),
    CONSTRAINT fk_holds_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_holds_status CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED')),
    CONSTRAINT ck_holds_amount CHECK (amount > 0 OR (tombstone AND amount = 0)),
    CONSTRAINT ck_holds_tombstone CHECK (NOT tombstone OR status = 'RELEASED')
);

CREATE INDEX idx_holds_account_status ON account_holds (account_id, status);

-- One row per applied transfer credit; the unique reference makes credits idempotent.
CREATE TABLE account_credits (
    id          BIGSERIAL      PRIMARY KEY,
    reference   VARCHAR(64)    NOT NULL,
    account_id  UUID           NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    created_by  VARCHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_credits_reference UNIQUE (reference),
    CONSTRAINT fk_credits_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_credits_amount CHECK (amount > 0)
);

ALTER TABLE account_audit DROP CONSTRAINT ck_audit_operation;
ALTER TABLE account_audit ADD CONSTRAINT ck_audit_operation
    CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_DEBIT', 'TRANSFER_CREDIT'));
