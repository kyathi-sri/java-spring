package com.securebank.transfer.config;

import com.securebank.common.event.EventTopics;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("securebank")
public record MessagingProperties(@DefaultValue Outbox outbox, @DefaultValue Kafka kafka) {

    public record Outbox(@DefaultValue("true") boolean enabled, @DefaultValue("500") long intervalMs,
                         @DefaultValue("50") int batchSize, @DefaultValue("5s") Duration sendTimeout) {
    }

    public record Kafka(@DefaultValue Topics topics) {
    }

    public record Topics(@DefaultValue(EventTopics.TRANSFER_EVENTS) String transferEvents,
                         @DefaultValue(EventTopics.TRANSFER_COMMANDS) String transferCommands) {
    }
}
