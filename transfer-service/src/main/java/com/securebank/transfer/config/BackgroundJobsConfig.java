package com.securebank.transfer.config;

import com.securebank.transfer.messaging.OutboxRelay;
import com.securebank.transfer.service.TransferRecoveryJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Schedulers are separate, switchable beans so tests can disable them and drive the workers
 * deterministically.
 */
@Configuration
public class BackgroundJobsConfig {

    @Bean
    @ConditionalOnProperty(name = "securebank.transfer.recovery.enabled", havingValue = "true", matchIfMissing = true)
    RecoveryScheduler recoveryScheduler(TransferRecoveryJob job) {
        return new RecoveryScheduler(job);
    }

    @Bean
    @ConditionalOnProperty(name = "securebank.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxScheduler outboxScheduler(OutboxRelay relay) {
        return new OutboxScheduler(relay);
    }

    /**
     * Kafka listener errors (only dependency outages are re-thrown) are retried with exponential
     * backoff up to 30s between attempts and no overall limit, so commands are never silently dropped.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        return new DefaultErrorHandler(backOff);
    }

    public static class RecoveryScheduler {
        private final TransferRecoveryJob job;

        RecoveryScheduler(TransferRecoveryJob job) {
            this.job = job;
        }

        @Scheduled(fixedDelayString = "${securebank.transfer.recovery.interval-ms:5000}",
                initialDelayString = "${securebank.transfer.recovery.interval-ms:5000}")
        public void run() {
            job.runOnce();
        }
    }

    public static class OutboxScheduler {
        private final OutboxRelay relay;

        OutboxScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${securebank.outbox.interval-ms:500}")
        public void run() {
            relay.publishPending();
        }
    }
}
