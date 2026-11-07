package com.securebank.transfer.domain;

/**
 * Persisted saga progress. Each value records what has been <em>confirmed</em> so far, so a restarted
 * worker knows exactly which (idempotent) call to make next.
 *
 * <pre>
 * Forward:      CREATED -> FUNDS_RESERVED -> LEDGER_POSTED -> BENEFICIARY_CREDITED -> COMPLETED
 * Compensation: (ledger rejected)        FUNDS_RESERVED -> RELEASING_FUNDS -> FAILED
 *               (beneficiary rejected)   LEDGER_POSTED  -> REVERSING_LEDGER -> RELEASING_FUNDS -> FAILED
 *               (reservation timed out)  CREATED -> RELEASING_FUNDS (tombstone) -> FAILED
 *               (reservation rejected)   CREATED -> FAILED (nothing to undo)
 * </pre>
 */
public enum SagaStep {
    CREATED,
    FUNDS_RESERVED,
    LEDGER_POSTED,
    BENEFICIARY_CREDITED,
    COMPLETED,
    REVERSING_LEDGER,
    RELEASING_FUNDS,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
