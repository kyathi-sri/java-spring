-- account-service owns these tables. No other service may read or write them.

CREATE TABLE accounts (
    id             UUID           PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL,
    owner_id       VARCHAR(64)    NOT NULL,           -- JWT subject of the customer
    customer_name  VARCHAR(120)   NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    balance        NUMERIC(19, 2) NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0,  -- optimistic locking
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT ck_accounts_status  CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_balance CHECK (balance >= 0)   -- last line of defence vs overdraft
);

CREATE INDEX idx_accounts_owner ON accounts (owner_id);

-- Append-only audit trail for every balance movement (never updated or deleted).
CREATE TABLE account_audit (
    id             BIGSERIAL      PRIMARY KEY,
    account_id     UUID           NOT NULL,
    operation      VARCHAR(16)    NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    balance_after  NUMERIC(19, 2) NOT NULL,
    reference      VARCHAR(64),
    correlation_id VARCHAR(64),
    performed_by   VARCHAR(64),
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_audit_operation CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_audit_amount CHECK (amount > 0)
);

CREATE INDEX idx_audit_account_created ON account_audit (account_id, created_at);
