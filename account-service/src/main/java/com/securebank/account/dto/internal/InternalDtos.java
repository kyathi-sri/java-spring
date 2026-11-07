package com.securebank.account.dto.internal;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountCredit;
import com.securebank.account.domain.AccountHold;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.HoldStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** DTOs of the service-to-service API ({@code /api/internal/**}, SERVICE role only). */
public final class InternalDtos {

    private InternalDtos() {
    }

    /** What another service needs to validate a transfer; includes the owner for ownership checks. */
    public record AccountSummary(UUID id, String accountNumber, String ownerId, String currency,
                                 AccountStatus status) {
        public static AccountSummary from(Account a) {
            return new AccountSummary(a.getId(), a.getAccountNumber(), a.getOwnerId(), a.getCurrency(), a.getStatus());
        }
    }

    public record HoldRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid characters")
            String reference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency
    ) {
    }

    public record CreditRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid characters")
            String reference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency
    ) {
    }

    /** @param replayed true when the call was an idempotent repeat of an already-applied operation */
    public record HoldResponse(String reference, UUID accountId, BigDecimal amount, String currency,
                               HoldStatus status, boolean tombstone, boolean replayed,
                               Instant createdAt, Instant updatedAt) {
        public static HoldResponse from(AccountHold h, boolean replayed) {
            return new HoldResponse(h.getReference(), h.getAccountId(), h.getAmount(), h.getCurrency(),
                    h.getStatus(), h.isTombstone(), replayed, h.getCreatedAt(), h.getUpdatedAt());
        }
    }

    public record CreditResponse(String reference, UUID accountId, BigDecimal amount, String currency,
                                 boolean replayed, Instant createdAt) {
        public static CreditResponse from(AccountCredit c, boolean replayed) {
            return new CreditResponse(c.getReference(), c.getAccountId(), c.getAmount(), c.getCurrency(),
                    replayed, c.getCreatedAt());
        }
    }
}
