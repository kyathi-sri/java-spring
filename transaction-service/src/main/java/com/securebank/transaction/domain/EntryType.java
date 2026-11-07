package com.securebank.transaction.domain;

public enum EntryType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    /** Created only by the reversal workflow, never accepted from a posting request. */
    REVERSAL,
    ADJUSTMENT
}
