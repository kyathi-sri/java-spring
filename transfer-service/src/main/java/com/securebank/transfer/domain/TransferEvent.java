package com.securebank.transfer.domain;

import com.securebank.common.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload published to {@code securebank.transfer.events}. Delivery is at-least-once, so consumers
 * must de-duplicate on {@link #eventId()} (also sent as the {@code eventId} Kafka header).
 */
public record TransferEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        UUID transferId,
        String reference,
        TransferStatus status,
        UUID sourceAccountId,
        String sourceOwnerId,
        UUID beneficiaryAccountId,
        String beneficiaryOwnerId,
        BigDecimal amount,
        String currency,
        String failureCode,
        String correlationId
) implements DomainEvent {

    public enum Type {
        TRANSFER_INITIATED,
        TRANSFER_COMPLETED,
        TRANSFER_FAILED
    }

    public static TransferEvent of(Transfer t, Type type) {
        return new TransferEvent(UUID.randomUUID().toString(), type.name(), Instant.now(), t.getId(),
                t.getReference(), t.getStatus(), t.getSourceAccountId(), t.getSourceOwnerId(),
                t.getBeneficiaryAccountId(), t.getBeneficiaryOwnerId(), t.getAmount(), t.getCurrency(),
                t.getFailureCode(), t.getCorrelationId());
    }
}
