package com.securebank.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(@NotBlank @Size(max = 200) String reason) {
}
