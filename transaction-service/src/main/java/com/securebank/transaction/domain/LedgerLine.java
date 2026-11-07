package com.securebank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/** One debit or credit posting. Immutable: corrections are made with reversal entries. */
@Entity
@Immutable
@Table(name = "ledger_lines")
public class LedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Bidirectional (mappedBy on the parent) so Hibernate never issues an UPDATE to set the FK.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    private JournalEntry journalEntry;

    @Column(name = "account_ref", nullable = false, updatable = false, length = 64)
    private String accountRef;

    @Column(name = "owner_id", updatable = false, length = 64)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    protected LedgerLine() {
        // JPA
    }

    LedgerLine(JournalEntry journalEntry, String accountRef, String ownerId, Direction direction,
               BigDecimal amount, String currency, Instant postedAt) {
        this.journalEntry = journalEntry;
        this.accountRef = accountRef;
        this.ownerId = ownerId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.postedAt = postedAt;
    }

    public Long getId() { return id; }
    public JournalEntry getJournalEntry() { return journalEntry; }
    public String getAccountRef() { return accountRef; }
    public String getOwnerId() { return ownerId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getPostedAt() { return postedAt; }
}
