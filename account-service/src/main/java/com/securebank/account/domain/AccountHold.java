package com.securebank.account.domain;

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
 * A reservation of funds. State changes are only made while the owning account row is locked
 * (see {@code TransferSupportService}), so hold transitions and balance changes stay atomic.
 */
@Entity
@Table(name = "account_holds")
public class AccountHold {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 64)
    private String reference;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HoldStatus status;

    @Column(nullable = false, updatable = false)
    private boolean tombstone;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AccountHold() {
        // JPA
    }

    private AccountHold(String reference, UUID accountId, BigDecimal amount, String currency,
                        HoldStatus status, boolean tombstone, String createdBy) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.tombstone = tombstone;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static AccountHold held(String reference, UUID accountId, BigDecimal amount, String currency,
                                   String createdBy) {
        return new AccountHold(reference, accountId, amount, currency, HoldStatus.HELD, false, createdBy);
    }

    /** Marker for "release arrived first": blocks any later reserve with the same reference. */
    public static AccountHold tombstone(String reference, UUID accountId, String currency, String createdBy) {
        return new AccountHold(reference, accountId, BigDecimal.ZERO.setScale(2), currency,
                HoldStatus.RELEASED, true, createdBy);
    }

    public void capture() {
        requireHeld();
        this.status = HoldStatus.CAPTURED;
        this.updatedAt = Instant.now();
    }

    public void release() {
        requireHeld();
        this.status = HoldStatus.RELEASED;
        this.updatedAt = Instant.now();
    }

    private void requireHeld() {
        if (status != HoldStatus.HELD) {
            throw new IllegalStateException("Hold " + reference + " is " + status);
        }
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public boolean isTombstone() { return tombstone; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
