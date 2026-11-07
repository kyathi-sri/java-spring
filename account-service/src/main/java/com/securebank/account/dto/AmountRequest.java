package com.securebank.account.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @DecimalMax(value = "1000000.00", message = "exceeds the per-transaction limit")
        @Digits(integer = 7, fraction = 2, message = "at most 2 decimal places")
        BigDecimal amount,

        // Restricted charset: the value is logged/audited, so no control characters.
        @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "invalid characters")
        String reference
) {
}
