package com.securebank.account.domain;

public enum AuditOperation {
    DEPOSIT,
    WITHDRAWAL,
    /** Source side of a transfer: a captured hold. */
    TRANSFER_DEBIT,
    /** Beneficiary side of a transfer. */
    TRANSFER_CREDIT
}
