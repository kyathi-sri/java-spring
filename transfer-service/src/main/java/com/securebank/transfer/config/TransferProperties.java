package com.securebank.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("securebank.transfer")
public record TransferProperties(
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("1s") Duration retryBaseDelay,
        @DefaultValue("60s") Duration retryMaxDelay,
        @DefaultValue("10") int stuckAlertAttempts,
        @DefaultValue("10m") Duration reservationTimeout,
        @DefaultValue Recovery recovery
) {
    public record Recovery(@DefaultValue("true") boolean enabled, @DefaultValue("5000") long intervalMs,
                           @DefaultValue("50") int batchSize) {
    }

    /** Exponential backoff: base * 2^attempts, capped at retryMaxDelay. */
    public Duration backoff(int attemptsSoFar) {
        if (retryBaseDelay.isZero()) {
            return Duration.ZERO;
        }
        long factor = 1L << Math.min(attemptsSoFar, 20);
        Duration d = retryBaseDelay.multipliedBy(factor);
        return d.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : d;
    }
}
