package com.securebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Record of an applied transfer credit. Its unique reference makes credits idempotent. */
@Entity
@Immutable
@Table(name = "account_credits")
public class AccountCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String reference;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountCredit() {
        // JPA
    }

    public AccountCredit(String reference, UUID accountId, BigDecimal amount, String currency, String createdBy) {
        this.reference = reference;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
