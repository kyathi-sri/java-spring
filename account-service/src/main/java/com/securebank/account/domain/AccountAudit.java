package com.securebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit record of a balance movement. All columns are non-updatable. */
@Entity
@Table(name = "account_audit")
public class AccountAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AuditOperation operation;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(updatable = false, length = 64)
    private String reference;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "performed_by", updatable = false, length = 64)
    private String performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountAudit() {
        // JPA
    }

    public AccountAudit(UUID accountId, AuditOperation operation, BigDecimal amount,
                        BigDecimal balanceAfter, String reference, String correlationId,
                        String performedBy) {
        this.accountId = accountId;
        this.operation = operation;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
        this.correlationId = correlationId;
        this.performedBy = performedBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public AuditOperation getOperation() { return operation; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getReference() { return reference; }
    public String getCorrelationId() { return correlationId; }
    public String getPerformedBy() { return performedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
