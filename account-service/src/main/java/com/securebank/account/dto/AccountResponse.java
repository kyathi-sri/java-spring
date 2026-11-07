package com.securebank.account.dto;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code balance} is the current (book) balance. {@code availableBalance}/{@code reservedAmount} were added. */
public record AccountResponse(
        UUID id,
        String accountNumber,
        String customerName,
        String currency,
        AccountStatus status,
        BigDecimal balance,
        Instant createdAt,
        BigDecimal availableBalance,
        BigDecimal reservedAmount
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getAccountNumber(), a.getCustomerName(),
                a.getCurrency(), a.getStatus(), a.getBalance(), a.getCreatedAt(),
                a.getAvailableBalance(), a.getReservedBalance());
    }
}
