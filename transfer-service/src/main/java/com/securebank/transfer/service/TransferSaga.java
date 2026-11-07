package com.securebank.transfer.service;

import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.transfer.client.AccountClient;
import com.securebank.transfer.client.ClientViews.CreditView;
import com.securebank.transfer.client.ClientViews.HoldView;
import com.securebank.transfer.client.ClientViews.LedgerEntryView;
import com.securebank.transfer.client.ClientViews.TransferPosting;
import com.securebank.transfer.client.LedgerClient;
import com.securebank.transfer.client.Lookup.Found;
import com.securebank.transfer.client.Lookup.NotFound;
import com.securebank.transfer.client.Lookup.Unavailable;
import com.securebank.transfer.client.RemoteResult.Rejected;
import com.securebank.transfer.client.RemoteResult.Success;
import com.securebank.transfer.client.RemoteResult.Unknown;
import com.securebank.transfer.config.TransferProperties;
import com.securebank.transfer.domain.SagaStep;
import com.securebank.transfer.domain.Transfer;
import com.securebank.transfer.domain.TransferEvent;
import com.securebank.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static com.securebank.transfer.domain.SagaStep.BENEFICIARY_CREDITED;
import static com.securebank.transfer.domain.SagaStep.COMPLETED;
import static com.securebank.transfer.domain.SagaStep.FAILED;
import static com.securebank.transfer.domain.SagaStep.FUNDS_RESERVED;
import static com.securebank.transfer.domain.SagaStep.LEDGER_POSTED;
import static com.securebank.transfer.domain.SagaStep.RELEASING_FUNDS;
import static com.securebank.transfer.domain.SagaStep.REVERSING_LEDGER;

/**
 * Orchestrated saga for one transfer.
 *
 * <h2>Why not a single database transaction?</h2>
 * The source balance, beneficiary balance and ledger live in three databases owned by different
 * services. No local transaction spans them, and distributed 2PC/XA would couple the services'
 * availability and hold locks across the network. Instead each step is a local ACID transaction in
 * its owning service, made idempotent by the transfer reference, and failures are undone by
 * <em>compensating</em> steps (release a hold, post a ledger reversal) rather than by rollback.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Only a definitive rejection ({@code Rejected}) counts as failure. {@code Unknown} (timeout, 5xx,
 *       connection error) is resolved by checking persisted status; if that is inconclusive the step
 *       is retried later with backoff. Retrying is always safe because every call is idempotent.</li>
 *   <li>Progress is persisted after every confirmed step, so a crashed worker's successor
 *       resumes exactly where it stopped.</li>
 *   <li>A lease ensures one worker per transfer; a dead worker's lease simply expires.</li>
 *   <li>Committed ledger entries are never modified: compensation posts a REVERSAL entry.</li>
 *   <li>The reservation is captured (money leaves the payer) only after the ledger entry exists and
 *       the beneficiary was credited, so nothing ever needs to be clawed back from a beneficiary.</li>
 * </ul>
 */
@Component
public class TransferSaga {

    private static final Logger log = LoggerFactory.getLogger(TransferSaga.class);
    private static final int MAX_STEPS_PER_RUN = 12;

    private final TransferStateStore store;
    private final AccountClient accounts;
    private final LedgerClient ledger;
    private final TransferProperties props;

    public TransferSaga(TransferStateStore store, AccountClient accounts, LedgerClient ledger,
                        TransferProperties props) {
        this.store = store;
        this.accounts = accounts;
        this.ledger = ledger;
        this.props = props;
    }

    /**
     * Drives the transfer as far as possible. Safe to call any number of times, from any thread or
     * instance: if another worker holds the lease this returns immediately.
     */
    public void advance(UUID transferId) {
        String owner = "worker-" + UUID.randomUUID();
        if (!store.tryClaim(transferId, owner)) {
            return;
        }
        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        boolean releaseLease = true;
        try {
            for (int i = 0; i < MAX_STEPS_PER_RUN; i++) {
                Transfer t = store.load(transferId);
                if (t.getCorrelationId() != null) {
                    MDC.put(CorrelationIdFilter.MDC_KEY, t.getCorrelationId());
                }
                if (t.isTerminal() || !runStep(t, owner)) {
                    return;
                }
                store.renewLease(transferId, owner);
            }
        } catch (LeaseLostException | OptimisticLockingFailureException e) {
            releaseLease = false;
            log.info("Lease lost for transfer {}; another worker continues", transferId);
        } catch (Error fatal) {
            // Behave like a crash: keep the lease so it expires and the recovery job resumes.
            releaseLease = false;
            throw fatal;
        } finally {
            if (releaseLease) {
                store.releaseLease(transferId, owner);
            }
            restoreMdc(previousCorrelationId);
        }
    }

    /** @return true to continue with the next step, false to stop (terminal or waiting for a retry) */
    private boolean runStep(Transfer t, String owner) {
        Transfer current = t;
        if (current.getStatus() == TransferStatus.PENDING) {
            current = store.apply(current.getId(), owner, current.getStep(), Transfer::markProcessing,
                    "Processing started", null);
        }
        try {
            return switch (current.getStep()) {
                case CREATED -> reserveFunds(current, owner);
                case FUNDS_RESERVED -> postLedger(current, owner);
                case LEDGER_POSTED -> creditBeneficiary(current, owner);
                case BENEFICIARY_CREDITED -> captureFunds(current, owner);
                case REVERSING_LEDGER -> reverseLedger(current, owner);
                case RELEASING_FUNDS -> releaseFunds(current, owner);
                case COMPLETED, FAILED -> false;
            };
        } catch (LeaseLostException | OptimisticLockingFailureException e) {
            throw e;
        } catch (RuntimeException unexpected) {
            log.error("Unexpected error at step {} of {}", current.getStep(), current.getReference(), unexpected);
            return retry(current, owner, "Unexpected error: " + unexpected.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ forward steps

    private boolean reserveFunds(Transfer t, String owner) {
        if (Instant.now().isAfter(t.getReserveDeadline())) {
            // Give up, but never assume the earlier reserve failed: release (or tombstone) the hold first.
            return moveTo(t, owner, RELEASING_FUNDS,
                    tr -> tr.recordFailure("RESERVATION_TIMEOUT", "Funds could not be reserved before the deadline"),
                    "Reservation deadline passed; cancelling any late hold");
        }
        return switch (accounts.placeHold(t.getSourceAccountId(), t.getReference(), t.getAmount(), t.getCurrency())) {
            case Success<HoldView> s -> moveTo(t, owner, FUNDS_RESERVED, null, "Funds reserved");
            case Rejected<HoldView> r -> moveTo(t, owner, FAILED, tr -> tr.recordFailure(r.code(), r.message()),
                    "Reservation rejected (" + r.code() + "); nothing to compensate");
            case Unknown<HoldView> u -> switch (accounts.findHold(t.getSourceAccountId(), t.getReference())) {
                case Found<HoldView> f when "HELD".equals(f.value().status()) -> moveTo(t, owner, FUNDS_RESERVED, null,
                        "Reserve outcome unknown (" + u.reason() + "); status check confirmed hold");
                case Found<HoldView> f when "RELEASED".equals(f.value().status()) -> moveTo(t, owner, FAILED,
                        tr -> tr.recordFailure("HOLD_RELEASED", "The reservation was cancelled"),
                        "Status check found the hold already released");
                case Found<HoldView> f -> retry(t, owner, "Unexpected hold status " + f.value().status());
                case NotFound<HoldView> n -> retry(t, owner, "Reserve outcome unknown (" + u.reason() + "); no hold yet");
                case Unavailable<HoldView> x -> retry(t, owner, "Reserve outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    private boolean postLedger(Transfer t, String owner) {
        TransferPosting posting = new TransferPosting(t.getReference(), "Transfer " + t.getReference(),
                t.getSourceAccountId(), t.getSourceOwnerId(), t.getBeneficiaryAccountId(), t.getBeneficiaryOwnerId(),
                t.getAmount(), t.getCurrency());
        return switch (ledger.postTransfer(posting)) {
            case Success<LedgerEntryView> s -> ledgerPosted(t, owner, s.value(), "Ledger entry posted");
            case Rejected<LedgerEntryView> r -> moveTo(t, owner, RELEASING_FUNDS,
                    tr -> tr.recordFailure(r.code(), r.message()), "Ledger rejected the posting (" + r.code() + ")");
            case Unknown<LedgerEntryView> u -> switch (ledger.findByReference(t.getReference())) {
                case Found<LedgerEntryView> f -> ledgerPosted(t, owner, f.value(),
                        "Posting outcome unknown (" + u.reason() + "); status check found the entry");
                case NotFound<LedgerEntryView> n -> retry(t, owner, "Posting outcome unknown (" + u.reason()
                        + "); entry not found yet");
                case Unavailable<LedgerEntryView> x -> retry(t, owner, "Posting outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    private boolean ledgerPosted(Transfer t, String owner, LedgerEntryView entry, String detail) {
        return moveTo(t, owner, LEDGER_POSTED, tr -> tr.recordLedgerEntry(entry.entryId()),
                detail + " " + entry.entryId());
    }

    private boolean creditBeneficiary(Transfer t, String owner) {
        return switch (accounts.credit(t.getBeneficiaryAccountId(), t.getReference(), t.getAmount(), t.getCurrency())) {
            case Success<CreditView> s -> moveTo(t, owner, BENEFICIARY_CREDITED, null, "Beneficiary credited");
            case Rejected<CreditView> r -> moveTo(t, owner, REVERSING_LEDGER,
                    tr -> tr.recordFailure(r.code(), r.message()),
                    "Beneficiary credit rejected (" + r.code() + "); compensating");
            case Unknown<CreditView> u -> switch (accounts.findCredit(t.getBeneficiaryAccountId(), t.getReference())) {
                case Found<CreditView> f -> moveTo(t, owner, BENEFICIARY_CREDITED, null,
                        "Credit outcome unknown (" + u.reason() + "); status check found the credit");
                case NotFound<CreditView> n -> retry(t, owner, "Credit outcome unknown (" + u.reason()
                        + "); credit not found yet");
                case Unavailable<CreditView> x -> retry(t, owner, "Credit outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    private boolean captureFunds(Transfer t, String owner) {
        return switch (accounts.captureHold(t.getSourceAccountId(), t.getReference())) {
            case Success<HoldView> s -> moveTo(t, owner, COMPLETED, null, "Reservation captured; transfer complete");
            // Only this saga releases holds, and never after crediting, so this means manual tampering.
            case Rejected<HoldView> r -> alertAndRetry(t, owner, "Capture rejected (" + r.code()
                    + ") after the beneficiary was credited: manual review required");
            case Unknown<HoldView> u -> switch (accounts.findHold(t.getSourceAccountId(), t.getReference())) {
                case Found<HoldView> f when "CAPTURED".equals(f.value().status()) -> moveTo(t, owner, COMPLETED, null,
                        "Capture outcome unknown (" + u.reason() + "); status check confirmed capture");
                case Found<HoldView> f -> retry(t, owner, "Capture outcome unknown; hold is " + f.value().status());
                case NotFound<HoldView> n -> alertAndRetry(t, owner, "Hold missing at capture: manual review required");
                case Unavailable<HoldView> x -> retry(t, owner, "Capture outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    // ------------------------------------------------------------------ compensation

    private boolean reverseLedger(Transfer t, String owner) {
        String reason = "Transfer " + t.getReference() + " failed: " + t.getFailureCode();
        return switch (ledger.reverse(t.getLedgerEntryId(), reason)) {
            case Success<LedgerEntryView> s -> moveTo(t, owner, RELEASING_FUNDS, null,
                    "Ledger entry reversed by " + s.value().entryId());
            case Rejected<LedgerEntryView> r -> alertAndRetry(t, owner, "Ledger reversal rejected (" + r.code() + ")");
            case Unknown<LedgerEntryView> u -> switch (ledger.findByReference("REV-" + t.getReference())) {
                case Found<LedgerEntryView> f -> moveTo(t, owner, RELEASING_FUNDS, null,
                        "Reversal outcome unknown (" + u.reason() + "); status check found reversal "
                                + f.value().entryId());
                case NotFound<LedgerEntryView> n -> retry(t, owner, "Reversal outcome unknown (" + u.reason()
                        + "); reversal not found yet");
                case Unavailable<LedgerEntryView> x -> retry(t, owner, "Reversal outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    private boolean releaseFunds(Transfer t, String owner) {
        return switch (accounts.releaseHold(t.getSourceAccountId(), t.getReference())) {
            case Success<HoldView> s -> moveTo(t, owner, FAILED, null,
                    s.value().tombstone() ? "No hold existed; tombstone written" : "Reservation released");
            case Rejected<HoldView> r -> alertAndRetry(t, owner, "Release rejected (" + r.code()
                    + "): manual review required");
            case Unknown<HoldView> u -> switch (accounts.findHold(t.getSourceAccountId(), t.getReference())) {
                case Found<HoldView> f when "RELEASED".equals(f.value().status()) -> moveTo(t, owner, FAILED, null,
                        "Release outcome unknown (" + u.reason() + "); status check confirmed release");
                case Found<HoldView> f -> retry(t, owner, "Release outcome unknown; hold is " + f.value().status());
                case NotFound<HoldView> n -> retry(t, owner, "Release outcome unknown; hold not found yet");
                case Unavailable<HoldView> x -> retry(t, owner, "Release outcome unknown; status check failed: "
                        + x.reason());
            };
        };
    }

    // ------------------------------------------------------------------ transitions

    private boolean moveTo(Transfer t, String owner, SagaStep next, Consumer<Transfer> extra, String detail) {
        TransferEvent.Type event = switch (next) {
            case COMPLETED -> TransferEvent.Type.TRANSFER_COMPLETED;
            case FAILED -> TransferEvent.Type.TRANSFER_FAILED;
            default -> null;
        };
        Transfer after = store.apply(t.getId(), owner, t.getStep(), tr -> {
            if (extra != null) {
                extra.accept(tr);
            }
            tr.moveTo(next);
        }, detail, event);
        if (next.isTerminal()) {
            log.info("Transfer {} {} (failureCode={})", after.getReference(), after.getStatus(), after.getFailureCode());
        }
        return !next.isTerminal();
    }

    private boolean retry(Transfer t, String owner, String reason) {
        Duration delay = props.backoff(t.getAttempts());
        Instant next = Instant.now().plus(delay);
        store.apply(t.getId(), owner, t.getStep(), tr -> tr.scheduleRetry(reason, next),
                "Retry in " + delay.toMillis() + "ms: " + reason, null);
        if (t.getAttempts() + 1 >= props.stuckAlertAttempts()) {
            log.error("ALERT transfer {} stuck at {} after {} attempts: {}", t.getReference(), t.getStep(),
                    t.getAttempts() + 1, reason);
        } else {
            log.warn("Transfer {} step {} will be retried: {}", t.getReference(), t.getStep(), reason);
        }
        return false;
    }

    private boolean alertAndRetry(Transfer t, String owner, String reason) {
        log.error("ALERT transfer {} at {}: {}", t.getReference(), t.getStep(), reason);
        return retry(t, owner, reason);
    }

    private static void restoreMdc(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        } else {
            MDC.put(CorrelationIdFilter.MDC_KEY, previous);
        }
    }
}
