package com.securebank.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 120) String customerName,
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
        /* Honoured only for ADMIN callers (e.g. branch onboarding); customers always own what they create. */
        @Size(max = 64) String ownerId
) {
}
