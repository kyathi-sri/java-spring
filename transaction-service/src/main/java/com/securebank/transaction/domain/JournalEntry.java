package com.securebank.transaction.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A balanced set of ledger lines posted atomically. Never updated after insert
 * ({@link Immutable} here, plus DB triggers that reject UPDATE/DELETE/TRUNCATE).
 *
 * <p>Implements {@link Persistable} because the id is assigned in code: without it Spring Data
 * would call {@code merge()} instead of {@code persist()}, and the lines would not cascade.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 16)
    private EntryType entryType;

    @Column(updatable = false)
    private String description;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<LedgerLine> lines = new ArrayList<>();

    @Transient
    private boolean isNew = true;

    protected JournalEntry() {
        // JPA
    }

    public JournalEntry(String reference, EntryType entryType, String description, UUID reversesEntryId,
                        String correlationId, String createdBy) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.entryType = entryType;
        this.description = description;
        this.reversesEntryId = reversesEntryId;
        this.correlationId = correlationId;
        this.createdBy = createdBy;
        this.postedAt = Instant.now();
    }

    public void addLine(String accountRef, String ownerId, Direction direction, BigDecimal amount, String currency) {
        lines.add(new LedgerLine(this, accountRef, ownerId, direction, amount, currency, postedAt));
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public EntryType getEntryType() { return entryType; }
    public String getDescription() { return description; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getCorrelationId() { return correlationId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getPostedAt() { return postedAt; }
    public List<LedgerLine> getLines() { return Collections.unmodifiableList(lines); }
}
