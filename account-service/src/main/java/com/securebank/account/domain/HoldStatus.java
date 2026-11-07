package com.securebank.account.domain;

/** HELD -> CAPTURED (funds leave the account) or HELD -> RELEASED (funds become available again). */
public enum HoldStatus {
    HELD,
    CAPTURED,
    RELEASED
}
