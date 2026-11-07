package com.securebank.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transfer aggregate and saga state.
 *
 * <p>The lease columns are managed only by native SQL in {@code TransferRepository} (read-only here):
 * a claim also bumps {@link #version}, so a worker that lost its lease fails its next save with an
 * optimistic-lock error instead of overwriting the new owner's progress.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 40)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "initiated_by", nullable = false, updatable = false, length = 64)
    private String initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Channel channel;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "source_owner_id", nullable = false, updatable = false, length = 64)
    private String sourceOwnerId;

    @Column(name = "beneficiary_account_id", nullable = false, updatable = false)
    private UUID beneficiaryAccountId;

    @Column(name = "beneficiary_owner_id", nullable = false, updatable = false, length = 64)
    private String beneficiaryOwnerId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 140)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStep step;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "reserve_deadline", nullable = false, updatable = false)
    private Instant reserveDeadline;

    @Column(name = "lease_owner", insertable = false, updatable = false, length = 64)
    private String leaseOwner;

    @Column(name = "lease_until", insertable = false, updatable = false)
    private Instant leaseUntil;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
        // JPA
    }

    @SuppressWarnings("java:S107") // a transfer genuinely has this many immutable facts
    public Transfer(String idempotencyKey, String initiatedBy, Channel channel, String requestHash,
                    UUID sourceAccountId, String sourceOwnerId, UUID beneficiaryAccountId,
                    String beneficiaryOwnerId, BigDecimal amount, String currency, String description,
                    Instant reserveDeadline, String correlationId) {
        this.id = UUID.randomUUID();
        this.reference = "TRF-" + UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
        this.channel = channel;
        this.requestHash = requestHash;
        this.sourceAccountId = sourceAccountId;
        this.sourceOwnerId = sourceOwnerId;
        this.beneficiaryAccountId = beneficiaryAccountId;
        this.beneficiaryOwnerId = beneficiaryOwnerId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.status = TransferStatus.PENDING;
        this.step = SagaStep.CREATED;
        this.reserveDeadline = reserveDeadline;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        if (status == TransferStatus.PENDING) {
            status = TransferStatus.PROCESSING;
            touch();
        }
    }

    /** Advance the saga. Resets the retry counter; status follows the step. */
    public void moveTo(SagaStep next) {
        this.step = next;
        this.status = switch (next) {
            case COMPLETED -> TransferStatus.COMPLETED;
            case FAILED -> TransferStatus.FAILED;
            default -> TransferStatus.PROCESSING;
        };
        this.attempts = 0;
        this.nextAttemptAt = null;
        this.lastError = null;
        touch();
        if (next.isTerminal()) {
            this.completedAt = this.updatedAt;
        }
    }

    public void recordLedgerEntry(UUID entryId) {
        this.ledgerEntryId = entryId;
    }

    /** Keeps the FIRST failure: it is the root cause; later compensation steps must not mask it. */
    public void recordFailure(String code, String reason) {
        if (this.failureCode == null) {
            this.failureCode = truncate(code, 64);
            this.failureReason = truncate(reason, 255);
        }
    }

    public void scheduleRetry(String error, Instant nextAttempt) {
        this.attempts++;
        this.lastError = truncate(error, 255);
        this.nextAttemptAt = nextAttempt;
        touch();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getInitiatedBy() { return initiatedBy; }
    public Channel getChannel() { return channel; }
    public String getRequestHash() { return requestHash; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getSourceOwnerId() { return sourceOwnerId; }
    public UUID getBeneficiaryAccountId() { return beneficiaryAccountId; }
    public String getBeneficiaryOwnerId() { return beneficiaryOwnerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public TransferStatus getStatus() { return status; }
    public SagaStep getStep() { return step; }
    public UUID getLedgerEntryId() { return ledgerEntryId; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getReserveDeadline() { return reserveDeadline; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getCorrelationId() { return correlationId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
