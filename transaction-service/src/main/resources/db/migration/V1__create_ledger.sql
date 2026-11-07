-- transaction-service owns these tables. No other service may read or write them.
--
-- Accounting convention (customer deposit accounts are LIABILITIES of the bank):
--   customer balance = SUM(CREDIT) - SUM(DEBIT)
--   deposit    : DEBIT bank cash,         CREDIT customer account
--   withdrawal : DEBIT customer account,  CREDIT bank cash
--   transfer   : DEBIT source account,    CREDIT beneficiary account
--   reversal   : a NEW entry with every line's direction flipped (history is never edited)

CREATE TABLE journal_entries (
    id               UUID         PRIMARY KEY,
    reference        VARCHAR(64)  NOT NULL,               -- idempotency key supplied by the caller
    entry_type       VARCHAR(16)  NOT NULL,
    description      VARCHAR(255),
    reverses_entry_id UUID,                                -- set only on REVERSAL entries
    correlation_id   VARCHAR(64),
    created_by       VARCHAR(64)  NOT NULL,
    posted_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_journal_reference UNIQUE (reference),
    -- UNIQUE here means an entry can be reversed at most once, even under races.
    CONSTRAINT uq_journal_reverses UNIQUE (reverses_entry_id),
    CONSTRAINT fk_journal_reverses FOREIGN KEY (reverses_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT ck_journal_type CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT ck_journal_reversal_link CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE TABLE ledger_lines (
    id               BIGSERIAL      PRIMARY KEY,
    journal_entry_id UUID           NOT NULL,
    account_ref      VARCHAR(64)    NOT NULL,   -- account UUID text, or an internal account such as CASH-SGD
    owner_id         VARCHAR(64),               -- customer id captured at posting time (null for internal accounts)
    direction        VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    posted_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_line_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT ck_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_line_amount CHECK (amount > 0)
);

CREATE INDEX idx_lines_account_posted ON ledger_lines (account_ref, posted_at);
CREATE INDEX idx_lines_entry ON ledger_lines (journal_entry_id);
CREATE INDEX idx_lines_account_owner ON ledger_lines (account_ref, owner_id);

-- ---------------------------------------------------------------------------
-- Immutability: posted ledger rows can never be updated, deleted or truncated.
-- ---------------------------------------------------------------------------
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_lines_append_only BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_journal_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_lines_no_truncate BEFORE TRUNCATE ON ledger_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();

-- ---------------------------------------------------------------------------
-- Double-entry guard: at COMMIT, debits must equal credits for every entry and currency.
-- Deferred so lines may be inserted one by one inside the same transaction.
-- ---------------------------------------------------------------------------
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    imbalance NUMERIC;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO imbalance
      FROM ledger_lines
     WHERE journal_entry_id = NEW.journal_entry_id AND currency = NEW.currency;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'Journal entry % is unbalanced by % %', NEW.journal_entry_id, imbalance, NEW.currency;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entry_balanced AFTER INSERT ON ledger_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
