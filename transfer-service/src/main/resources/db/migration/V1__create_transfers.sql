-- transfer-service owns these tables. No other service may read or write them.

CREATE TABLE transfers (
    id                     UUID           PRIMARY KEY,
    reference              VARCHAR(40)    NOT NULL,   -- TRF-<uuid>; idempotency key for every downstream call
    idempotency_key        VARCHAR(64)    NOT NULL,   -- client-supplied (Idempotency-Key header / command)
    initiated_by           VARCHAR(64)    NOT NULL,
    channel                VARCHAR(16)    NOT NULL,
    request_hash           VARCHAR(64)    NOT NULL,   -- SHA-256 of the payload, detects key re-use
    source_account_id      UUID           NOT NULL,
    source_owner_id        VARCHAR(64)    NOT NULL,
    beneficiary_account_id UUID           NOT NULL,
    beneficiary_owner_id   VARCHAR(64)    NOT NULL,
    amount                 NUMERIC(19, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    description            VARCHAR(140),
    status                 VARCHAR(16)    NOT NULL,
    step                   VARCHAR(32)    NOT NULL,   -- saga progress
    ledger_entry_id        UUID,
    failure_code           VARCHAR(64),
    failure_reason         VARCHAR(255),
    attempts               INT            NOT NULL DEFAULT 0,
    last_error             VARCHAR(255),
    next_attempt_at        TIMESTAMPTZ,
    reserve_deadline       TIMESTAMPTZ    NOT NULL,
    lease_owner            VARCHAR(64),
    lease_until            TIMESTAMPTZ,
    correlation_id         VARCHAR(64),
    version                BIGINT         NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL,
    updated_at             TIMESTAMPTZ    NOT NULL,
    completed_at           TIMESTAMPTZ,
    CONSTRAINT uq_transfers_reference UNIQUE (reference),
    CONSTRAINT uq_transfers_idempotency UNIQUE (initiated_by, idempotency_key),
    CONSTRAINT ck_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_transfers_distinct CHECK (source_account_id <> beneficiary_account_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_transfers_step CHECK (step IN ('CREATED', 'FUNDS_RESERVED', 'LEDGER_POSTED',
        'BENEFICIARY_CREDITED', 'COMPLETED', 'REVERSING_LEDGER', 'RELEASING_FUNDS', 'FAILED')),
    -- status and step can never disagree about whether the transfer is finished
    CONSTRAINT ck_transfers_terminal CHECK ((status IN ('COMPLETED', 'FAILED')) = (step IN ('COMPLETED', 'FAILED')))
);

-- recovery job: "which unfinished transfers are due?"
CREATE INDEX idx_transfers_due ON transfers (next_attempt_at) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_transfers_source ON transfers (source_account_id, created_at);

-- Append-only audit trail of every state change and retry.
CREATE TABLE transfer_history (
    id          BIGSERIAL    PRIMARY KEY,
    transfer_id UUID         NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16)  NOT NULL,
    from_step   VARCHAR(32),
    to_step     VARCHAR(32)  NOT NULL,
    detail      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_history_transfer FOREIGN KEY (transfer_id) REFERENCES transfers (id)
);
CREATE INDEX idx_history_transfer ON transfer_history (transfer_id, id);

CREATE FUNCTION forbid_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'transfer_history is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_history_append_only BEFORE UPDATE OR DELETE ON transfer_history
    FOR EACH ROW EXECUTE FUNCTION forbid_history_mutation();

-- Transactional outbox: written in the SAME transaction as the state change it describes.
CREATE TABLE outbox_events (
    id           BIGSERIAL    PRIMARY KEY,       -- publish order
    event_id     UUID         NOT NULL,          -- consumers de-duplicate on this
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    message_key  VARCHAR(64)  NOT NULL,          -- transfer reference: keeps one transfer's events ordered
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(255),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Inbox: Kafka messages already handled, so redelivered duplicates are ignored.
CREATE TABLE processed_messages (
    message_id   VARCHAR(100) PRIMARY KEY,
    source       VARCHAR(128) NOT NULL,
    outcome      VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
