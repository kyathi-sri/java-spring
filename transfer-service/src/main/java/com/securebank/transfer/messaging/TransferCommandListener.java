package com.securebank.transfer.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.error.BusinessException;
import com.securebank.transfer.domain.Channel;
import com.securebank.transfer.dto.TransferDtos.TransferCommand;
import com.securebank.transfer.exception.TransferExceptions.DependencyUnavailableException;
import com.securebank.transfer.security.Caller;
import com.securebank.transfer.service.TransferService;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Accepts transfer instructions from asynchronous channels (e.g. a scheduled-payments service).
 *
 * <p>Kafka delivers at-least-once, so duplicates are expected. Two layers stop a duplicate from
 * becoming a second transfer: the inbox ({@code processed_messages}, keyed by commandId), and the
 * per-customer unique Idempotency-Key shared with the REST API.
 *
 * <p>Business rejections are recorded and skipped (they will never succeed). Dependency outages
 * are re-thrown, so the container retries with backoff instead of dropping the command.
 */
@Component
@ConditionalOnProperty(name = "securebank.kafka.command-listener.enabled", havingValue = "true", matchIfMissing = true)
public class TransferCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TransferCommandListener.class);

    private final TransferService transfers;
    private final ObjectMapper mapper;
    private final Validator validator;

    public TransferCommandListener(TransferService transfers, ObjectMapper mapper, Validator validator) {
        this.transfers = transfers;
        this.mapper = mapper;
        this.validator = validator;
    }

    @KafkaListener(id = "transfer-commands", topics = "${securebank.kafka.topics.transfer-commands}",
            groupId = "transfer-service-commands")
    public void onCommand(ConsumerRecord<String, String> record) {
        TransferCommand command;
        try {
            command = mapper.readValue(record.value(), TransferCommand.class);
        } catch (JsonProcessingException e) {
            log.warn("Discarding malformed transfer command at {}-{}@{}", record.topic(), record.partition(),
                    record.offset());
            return;
        }
        if (!validator.validate(command).isEmpty()) {
            log.warn("Discarding invalid transfer command {}", command.commandId());
            if (command.commandId() != null && !command.commandId().isBlank() && command.commandId().length() <= 100) {
                transfers.recordRejectedCommand(command.commandId(), "INVALID_COMMAND");
            }
            return;
        }
        if (transfers.isMessageProcessed(command.commandId())) {
            log.info("Duplicate transfer command {} ignored", command.commandId());
            return;
        }

        MDC.put(CorrelationIdFilter.MDC_KEY, command.commandId());
        try {
            var result = transfers.create(command.toRequest(), command.idempotencyKey(),
                    Caller.customer(command.customerId()), Channel.KAFKA, command.commandId());
            log.info("Transfer command {} -> {} {}{}", command.commandId(), result.transfer().reference(),
                    result.transfer().status(), result.replayed() ? " (replay)" : "");
        } catch (DependencyUnavailableException e) {
            throw e; // retried by the container's error handler, never dropped
        } catch (BusinessException rejected) {
            log.warn("Transfer command {} rejected: {}", command.commandId(), rejected.getErrorCode());
            transfers.recordRejectedCommand(command.commandId(), rejected.getErrorCode());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
