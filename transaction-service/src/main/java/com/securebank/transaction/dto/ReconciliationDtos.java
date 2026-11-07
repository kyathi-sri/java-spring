package com.securebank.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DTOs for ledger reconciliation. */
public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    public record CurrencyTotals(String currency, BigDecimal totalDebits, BigDecimal totalCredits,
                                 long lineCount, boolean balanced) {
    }

    /** Ledger-wide integrity check: every currency balanced and no unbalanced entries. */
    public record TrialBalance(Instant generatedAt, List<CurrencyTotals> currencies,
                               long unbalancedEntries, boolean healthy) {
    }

    /** Balances reported by another system (e.g. account-service) to compare with the ledger. */
    public record AccountCheckRequest(@NotNull @Size(min = 1, max = 500) List<@Valid @NotNull Item> items) {
        public record Item(@NotBlank @Size(max = 64) String accountRef, @NotNull BigDecimal expectedBalance) {
        }
    }

    public record AccountCheckResult(String accountRef, BigDecimal expectedBalance, BigDecimal ledgerBalance,
                                     BigDecimal difference, boolean matched) {
    }

    public record AccountCheckReport(Instant generatedAt, int checked, int mismatched,
                                     List<AccountCheckResult> results) {
    }
}
