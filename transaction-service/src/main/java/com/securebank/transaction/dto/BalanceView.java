package com.securebank.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Ledger balance of an account strictly before {@code asOf} (credits minus debits). */
public record BalanceView(String accountRef, BigDecimal balance, Instant asOf) {
}
