package com.securebank.transfer.domain;

/** Customer-facing lifecycle: PENDING -> PROCESSING -> COMPLETED | FAILED. */
public enum TransferStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
