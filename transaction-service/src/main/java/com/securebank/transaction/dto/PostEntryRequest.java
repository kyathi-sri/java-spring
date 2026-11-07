package com.securebank.transaction.dto;

import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request to post a journal entry. {@code reference} is the idempotency key: re-sending the
 * same reference with the same lines returns the original entry instead of posting twice.
 */
public record PostEntryRequest(
        // Max 60 so that the derived reversal reference "REV-" + reference fits in 64.
        @NotBlank @Size(max = 60) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid characters")
        String reference,

        @NotNull EntryType entryType,

        @Size(max = 255) String description,

        @NotNull @Size(min = 2, max = 20) List<@Valid @NotNull Line> lines
) {
    public record Line(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "invalid characters")
            String accountRef,

            /* Customer who owns accountRef; null for internal accounts such as CASH-SGD. */
            @Size(max = 64) String ownerId,

            @NotNull Direction direction,

            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2)
            BigDecimal amount,

            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code")
            String currency
    ) {
    }
}
