package com.securebank.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** One row per state change or scheduled retry (append-only, enforced by a DB trigger). */
@Entity
@Immutable
@Table(name = "transfer_history")
public class TransferHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 16)
    private TransferStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 16)
    private TransferStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_step", updatable = false, length = 32)
    private SagaStep fromStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_step", nullable = false, updatable = false, length = 32)
    private SagaStep toStep;

    @Column(updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferHistory() {
        // JPA
    }

    public TransferHistory(Transfer after, TransferStatus fromStatus, SagaStep fromStep, String detail) {
        this.transferId = after.getId();
        this.fromStatus = fromStatus;
        this.toStatus = after.getStatus();
        this.fromStep = fromStep;
        this.toStep = after.getStep();
        this.detail = Transfer.truncate(detail, 255);
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public TransferStatus getFromStatus() { return fromStatus; }
    public TransferStatus getToStatus() { return toStatus; }
    public SagaStep getFromStep() { return fromStep; }
    public SagaStep getToStep() { return toStep; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
