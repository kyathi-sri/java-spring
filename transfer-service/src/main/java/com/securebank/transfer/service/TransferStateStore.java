package com.securebank.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transfer.config.MessagingProperties;
import com.securebank.transfer.config.TransferProperties;
import com.securebank.transfer.domain.OutboxEvent;
import com.securebank.transfer.domain.SagaStep;
import com.securebank.transfer.domain.Transfer;
import com.securebank.transfer.domain.TransferEvent;
import com.securebank.transfer.domain.TransferHistory;
import com.securebank.transfer.domain.TransferStatus;
import com.securebank.transfer.repository.OutboxEventRepository;
import com.securebank.transfer.repository.ProcessedMessageRepository;
import com.securebank.transfer.repository.TransferHistoryRepository;
import com.securebank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * All saga persistence. Every method is one short local transaction; remote calls are never made
 * while a database transaction is open.
 *
 * <p>Transactional outbox: a state change, its history row and the event describing it are
 * committed together, so an event can never be lost (state committed, event not) or invented
 * (event sent, state rolled back).
 */
@Component
public class TransferStateStore {

    private final TransferRepository transfers;
    private final TransferHistoryRepository history;
    private final OutboxEventRepository outbox;
    private final ProcessedMessageRepository inbox;
    private final TransferProperties props;
    private final MessagingProperties messaging;
    private final ObjectMapper mapper;

    public TransferStateStore(TransferRepository transfers, TransferHistoryRepository history,
                              OutboxEventRepository outbox, ProcessedMessageRepository inbox,
                              TransferProperties props, MessagingProperties messaging, ObjectMapper mapper) {
        this.transfers = transfers;
        this.history = history;
        this.outbox = outbox;
        this.inbox = inbox;
        this.props = props;
        this.messaging = messaging;
        this.mapper = mapper;
    }

    /**
     * Inserts a new transfer (PENDING) with its first history row and TRANSFER_INITIATED event, and
     * optionally the inbox marker of the Kafka message that created it, all in one transaction.
     * A duplicate idempotency key surfaces here as a DataIntegrityViolationException.
     */
    @Transactional
    public Transfer create(Transfer transfer, String messageId, String messageSource) {
        transfers.saveAndFlush(transfer);
        history.save(new TransferHistory(transfer, null, null, "Transfer accepted via " + transfer.getChannel()));
        outbox.save(event(transfer, TransferEvent.Type.TRANSFER_INITIATED));
        if (messageId != null) {
            inbox.record(messageId, messageSource, "TRANSFER_CREATED:" + transfer.getReference(), Instant.now());
        }
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer load(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new IllegalStateException("Transfer " + id + " vanished"));
    }

    /**
     * Applies one saga transition, guarded twice: the caller must still hold the lease, and the
     * transfer must still be at the step the caller acted on. Version checking catches the rest.
     */
    @Transactional
    public Transfer apply(UUID id, String owner, SagaStep expectedStep, Consumer<Transfer> change, String detail,
                          TransferEvent.Type event) {
        Transfer t = load(id);
        if (!owner.equals(t.getLeaseOwner()) || t.getStep() != expectedStep) {
            throw new LeaseLostException(t.getReference());
        }
        TransferStatus fromStatus = t.getStatus();
        SagaStep fromStep = t.getStep();
        change.accept(t);
        transfers.saveAndFlush(t);
        history.save(new TransferHistory(t, fromStatus, fromStep, detail));
        if (event != null) {
            outbox.save(event(t, event));
        }
        return t;
    }

    @Transactional
    public boolean tryClaim(UUID id, String owner) {
        Instant now = Instant.now();
        return transfers.claim(id, owner, now.plus(props.leaseDuration()), now) == 1;
    }

    @Transactional
    public void renewLease(UUID id, String owner) {
        transfers.renew(id, owner, Instant.now().plus(props.leaseDuration()));
    }

    @Transactional
    public void releaseLease(UUID id, String owner) {
        transfers.release(id, owner);
    }

    @Transactional
    public void recordMessage(String messageId, String source, String outcome) {
        inbox.record(messageId, source, outcome, Instant.now());
    }

    @Transactional(readOnly = true)
    public boolean isMessageProcessed(String messageId) {
        return inbox.exists(messageId);
    }

    private OutboxEvent event(Transfer t, TransferEvent.Type type) {
        TransferEvent payload = TransferEvent.of(t, type);
        try {
            return new OutboxEvent(UUID.fromString(payload.eventId()), t.getId(), type.name(),
                    messaging.kafka().topics().transferEvents(), t.getReference(), mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + type, e);
        }
    }
}
