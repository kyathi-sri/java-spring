package com.securebank.transfer.dto;

import com.securebank.transfer.domain.Channel;
import com.securebank.transfer.domain.SagaStep;
import com.securebank.transfer.domain.Transfer;
import com.securebank.transfer.domain.TransferHistory;
import com.securebank.transfer.domain.TransferStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID beneficiaryAccountId,
            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "1000000.00", message = "exceeds the per-transfer limit")
            @Digits(integer = 7, fraction = 2, message = "at most 2 decimal places")
            BigDecimal amount,
            /* Optional; defaults to the source account currency. */
            @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
            @Size(max = 140) @Pattern(regexp = "^[\\p{L}\\p{N} .,'/()&-]*$", message = "invalid characters")
            String description
    ) {
    }

    /** Transfer instruction from an asynchronous channel (topic {@code securebank.transfer.commands}). */
    public record TransferCommand(
            @NotBlank @Size(max = 100) String commandId,
            @NotBlank @Size(max = 64) String customerId,
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String idempotencyKey,
            @NotNull UUID sourceAccountId,
            @NotNull UUID beneficiaryAccountId,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Size(max = 140) @Pattern(regexp = "^[\\p{L}\\p{N} .,'/()&-]*$") String description
    ) {
        public CreateTransferRequest toRequest() {
            return new CreateTransferRequest(sourceAccountId, beneficiaryAccountId, amount, currency, description);
        }
    }

    public record TransferResponse(
            String reference,
            TransferStatus status,
            UUID sourceAccountId,
            UUID beneficiaryAccountId,
            BigDecimal amount,
            String currency,
            String description,
            String failureCode,
            String failureReason,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getReference(), t.getStatus(), t.getSourceAccountId(),
                    t.getBeneficiaryAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                    t.getFailureCode(), t.getFailureReason(), t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt());
        }
    }

    /** Staff-only operational view, including saga internals. */
    public record TransferAudit(
            String reference,
            TransferStatus status,
            SagaStep step,
            Channel channel,
            String initiatedBy,
            UUID ledgerEntryId,
            int attempts,
            String lastError,
            Instant nextAttemptAt,
            String correlationId,
            java.util.List<HistoryEntry> history
    ) {
    }

    public record HistoryEntry(TransferStatus fromStatus, TransferStatus toStatus, SagaStep fromStep, SagaStep toStep,
                               String detail, Instant at) {
        public static HistoryEntry from(TransferHistory h) {
            return new HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getFromStep(), h.getToStep(), h.getDetail(),
                    h.getCreatedAt());
        }
    }
}
