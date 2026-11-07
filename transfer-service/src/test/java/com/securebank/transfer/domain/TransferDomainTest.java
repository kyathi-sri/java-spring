package com.securebank.transfer.domain;

import com.securebank.transfer.config.TransferProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Transfer domain")
class TransferDomainTest {

    private static Transfer newTransfer() {
        return new Transfer("key-12345678", "alice", Channel.API, "hash", UUID.randomUUID(), "alice",
                UUID.randomUUID(), "bob", new BigDecimal("10.00"), "SGD", null, Instant.now().plusSeconds(600), "c1");
    }

    @Test
    @DisplayName("starts PENDING at CREATED with a TRF- reference")
    void initialState() {
        Transfer t = newTransfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.getStep()).isEqualTo(SagaStep.CREATED);
        assertThat(t.getReference()).startsWith("TRF-").hasSize(40);
    }

    @Test
    @DisplayName("status follows the step; terminal steps set completedAt and reset retries")
    void statusFollowsStep() {
        Transfer t = newTransfer();
        t.markProcessing();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROCESSING);

        t.scheduleRetry("boom", Instant.now());
        assertThat(t.getAttempts()).isEqualTo(1);

        t.moveTo(SagaStep.FUNDS_RESERVED);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(t.getAttempts()).isZero();
        assertThat(t.getLastError()).isNull();

        t.moveTo(SagaStep.COMPLETED);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getCompletedAt()).isNotNull();
        assertThat(t.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("compensation steps report PROCESSING, and only FAILED is terminal")
    void compensationStatus() {
        Transfer t = newTransfer();
        t.moveTo(SagaStep.REVERSING_LEDGER);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        t.moveTo(SagaStep.RELEASING_FUNDS);
        assertThat(t.isTerminal()).isFalse();
        t.moveTo(SagaStep.FAILED);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    @DisplayName("the first failure is kept as root cause")
    void firstFailureWins() {
        Transfer t = newTransfer();
        t.recordFailure("ACCOUNT_NOT_ACTIVE", "beneficiary closed");
        t.recordFailure("SOMETHING_ELSE", "later");
        assertThat(t.getFailureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("backoff doubles per attempt and is capped")
    void backoff() {
        TransferProperties p = new TransferProperties(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(60), 10, Duration.ofMinutes(10), new TransferProperties.Recovery(true, 5000, 50));
        assertThat(p.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.backoff(10)).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.backoff(500)).isEqualTo(Duration.ofSeconds(60));
    }
}
