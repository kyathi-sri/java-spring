package com.securebank.account.dto;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Backward compatible: the original six fields are unchanged; {@code currentBalance} and
 * {@code reservedAmount} were added. availableBalance = currentBalance - reservedAmount.
 */
public record BalanceResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        BigDecimal availableBalance,
        AccountStatus status,
        Instant asOf,
        BigDecimal currentBalance,
        BigDecimal reservedAmount
) {
    public static BalanceResponse from(Account a) {
        return new BalanceResponse(a.getId(), a.getAccountNumber(), a.getCurrency(),
                a.getAvailableBalance(), a.getStatus(), Instant.now(), a.getBalance(), a.getReservedBalance());
    }
}
