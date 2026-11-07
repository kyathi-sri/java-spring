package com.securebank.transfer.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Read-only mapping of the inbox table; writes go through {@link ProcessedMessageRepository#record}. */
@Entity
@Immutable
@Table(name = "processed_messages")
public class ProcessedMarker {

    @Id
    @jakarta.persistence.Column(name = "message_id")
    private String messageId;

    @jakarta.persistence.Column(name = "source")
    private String source;

    @jakarta.persistence.Column(name = "outcome")
    private String outcome;

    @jakarta.persistence.Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessedMarker() {
        // JPA
    }

    public String getMessageId() { return messageId; }
    public String getSource() { return source; }
    public String getOutcome() { return outcome; }
    public Instant getProcessedAt() { return processedAt; }
}
