package com.securebank.transfer.messaging;

import com.securebank.transfer.config.MessagingProperties;
import com.securebank.transfer.domain.OutboxEvent;
import com.securebank.transfer.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka in insertion order.
 *
 * <p>Guarantee: at-least-once. If Kafka acknowledges but the "published" update is lost (crash), the
 * event is sent again, so consumers de-duplicate on {@code eventId}. On the first failure the batch
 * stops, preserving per-transfer ordering. The producer is idempotent, so its own internal retries
 * do not create duplicates.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MessagingProperties props;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager txManager, MessagingProperties props) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
    }

    /** @return number of events published in this pass */
    public int publishPending() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.lockUnpublished(props.outbox().batchSize());
            int count = 0;
            for (OutboxEvent event : batch) {
                try {
                    kafka.send(toRecord(event)).get(props.outbox().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    event.markPublished();
                    count++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    event.recordFailure("Interrupted");
                    break;
                } catch (Exception e) {
                    event.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("Outbox publish failed for event {} ({}); will retry", event.getEventId(),
                            event.getEventType());
                    break;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent e) {
        ProducerRecord<String, String> record = new ProducerRecord<>(e.getTopic(), e.getMessageKey(), e.getPayload());
        record.headers().add("eventId", e.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", e.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateId", e.getAggregateId().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
