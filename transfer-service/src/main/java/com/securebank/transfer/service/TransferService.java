package com.securebank.transfer.service;

import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.money.Money;
import com.securebank.transfer.client.AccountClient;
import com.securebank.transfer.client.ClientViews.AccountSummary;
import com.securebank.transfer.client.RemoteResult;
import com.securebank.transfer.config.TransferProperties;
import com.securebank.transfer.domain.Channel;
import com.securebank.transfer.domain.Transfer;
import com.securebank.transfer.dto.TransferDtos.CreateTransferRequest;
import com.securebank.transfer.dto.TransferDtos.HistoryEntry;
import com.securebank.transfer.dto.TransferDtos.TransferAudit;
import com.securebank.transfer.dto.TransferDtos.TransferResponse;
import com.securebank.transfer.exception.TransferExceptions.DependencyUnavailableException;
import com.securebank.transfer.exception.TransferExceptions.IdempotencyConflictException;
import com.securebank.transfer.exception.TransferExceptions.InvalidTransferException;
import com.securebank.transfer.exception.TransferExceptions.SourceAccountNotFoundException;
import com.securebank.transfer.exception.TransferExceptions.TransferNotFoundException;
import com.securebank.transfer.exception.TransferExceptions.TransferRejectedException;
import com.securebank.transfer.repository.TransferHistoryRepository;
import com.securebank.transfer.repository.TransferRepository;
import com.securebank.transfer.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Accepts transfer instructions idempotently and hands them to the saga. */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /** @param replayed true when an earlier request with the same Idempotency-Key was returned */
    public record CreateResult(TransferResponse transfer, boolean replayed) {
    }

    private final TransferRepository transfers;
    private final TransferHistoryRepository history;
    private final TransferStateStore store;
    private final TransferSaga saga;
    private final AccountClient accounts;
    private final TransferProperties props;

    public TransferService(TransferRepository transfers, TransferHistoryRepository history, TransferStateStore store,
                           TransferSaga saga, AccountClient accounts, TransferProperties props) {
        this.transfers = transfers;
        this.history = history;
        this.store = store;
        this.saga = saga;
        this.accounts = accounts;
        this.props = props;
    }

    /**
     * Idempotent create:
     * <ol>
     *   <li>Same (caller, key) seen before: return that transfer (409 if the payload differs).</li>
     *   <li>Otherwise validate against account-service (ownership, beneficiary, currency).</li>
     *   <li>Insert PENDING + outbox event atomically; a concurrent duplicate loses on the unique
     *       constraint and is turned into a replay of the winner.</li>
     *   <li>Run the saga inline (best effort). Anything left unfinished is completed by the recovery job.</li>
     * </ol>
     */
    public CreateResult create(CreateTransferRequest request, String idempotencyKey, Caller caller, Channel channel,
                               String messageId) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidTransferException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters of letters, digits, '-' or '_'");
        }
        if (request.sourceAccountId().equals(request.beneficiaryAccountId())) {
            throw new InvalidTransferException("SAME_ACCOUNT", "Source and beneficiary accounts must differ");
        }
        BigDecimal2 amount = BigDecimal2.of(request);
        String hash = requestHash(request, amount);

        var existing = transfers.findByInitiatedByAndIdempotencyKey(caller.subject(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, messageId, channel);
        }

        AccountSummary source = loadSource(request.sourceAccountId(), caller);
        AccountSummary beneficiary = loadBeneficiary(request.beneficiaryAccountId());
        String currency = request.currency() != null ? request.currency() : source.currency();
        if (!currency.equals(source.currency()) || !currency.equals(beneficiary.currency())) {
            throw new TransferRejectedException("CURRENCY_MISMATCH", 422,
                    "Both accounts must hold " + currency + " (cross-currency transfers are not supported)");
        }
        if (!"ACTIVE".equals(source.status())) {
            throw new TransferRejectedException("ACCOUNT_NOT_ACTIVE", 409, "Source account is " + source.status());
        }

        String correlationId = CorrelationIdFilter.current() != null ? CorrelationIdFilter.current()
                : UUID.randomUUID().toString();
        Transfer transfer = new Transfer(idempotencyKey, caller.subject(), channel, hash, source.id(), source.ownerId(),
                beneficiary.id(), beneficiary.ownerId(), amount.value(), currency, request.description(),
                Instant.now().plus(props.reservationTimeout()), correlationId);
        try {
            store.create(transfer, messageId, messageId == null ? null : "kafka:" + channel);
        } catch (DataIntegrityViolationException race) {
            Transfer winner = transfers.findByInitiatedByAndIdempotencyKey(caller.subject(), idempotencyKey)
                    .orElseThrow(() -> race);
            return replay(winner, hash, messageId, channel);
        }
        log.info("Transfer {} accepted via {} for amount {} {}", transfer.getReference(), channel, amount.value(),
                currency);

        saga.advance(transfer.getId());
        return new CreateResult(TransferResponse.from(store.load(transfer.getId())), false);
    }

    private CreateResult replay(Transfer existing, String hash, String messageId, Channel channel) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException();
        }
        if (messageId != null) {
            store.recordMessage(messageId, "kafka:" + channel, "DUPLICATE_OF:" + existing.getReference());
        }
        if (!existing.isTerminal()) {
            saga.advance(existing.getId()); // nudge; a no-op if another worker holds the lease
        }
        return new CreateResult(TransferResponse.from(store.load(existing.getId())), true);
    }

    public TransferResponse get(String reference, Caller caller) {
        return TransferResponse.from(visible(reference, caller));
    }

    public TransferAudit audit(String reference) {
        Transfer t = transfers.findByReference(reference).orElseThrow(() -> new TransferNotFoundException(reference));
        return new TransferAudit(t.getReference(), t.getStatus(), t.getStep(), t.getChannel(), t.getInitiatedBy(),
                t.getLedgerEntryId(), t.getAttempts(), t.getLastError(), t.getNextAttemptAt(), t.getCorrelationId(),
                history.findByTransferIdOrderByIdAsc(t.getId()).stream().map(HistoryEntry::from).toList());
    }

    public void recordRejectedCommand(String messageId, String code) {
        store.recordMessage(messageId, "kafka:" + Channel.KAFKA, "REJECTED:" + code);
    }

    public boolean isMessageProcessed(String messageId) {
        return store.isMessageProcessed(messageId);
    }

    // ------------------------------------------------------------------ helpers

    private Transfer visible(String reference, Caller caller) {
        Transfer t = transfers.findByReference(reference).orElseThrow(() -> new TransferNotFoundException(reference));
        if (caller.staff() || t.getInitiatedBy().equals(caller.subject())
                || t.getSourceOwnerId().equals(caller.subject())) {
            return t;
        }
        throw new TransferNotFoundException(reference); // do not reveal other customers' transfers
    }

    private AccountSummary loadSource(UUID id, Caller caller) {
        return switch (accounts.getAccount(id)) {
            case RemoteResult.Success<AccountSummary> s -> {
                if (!caller.admin() && !s.value().ownerId().equals(caller.subject())) {
                    throw new SourceAccountNotFoundException();
                }
                yield s.value();
            }
            case RemoteResult.Rejected<AccountSummary> r when r.httpStatus() == 404 ->
                    throw new SourceAccountNotFoundException();
            case RemoteResult.Rejected<AccountSummary> r ->
                    throw new TransferRejectedException(r.code(), 422, "Source account check failed");
            case RemoteResult.Unknown<AccountSummary> u -> throw new DependencyUnavailableException();
        };
    }

    private AccountSummary loadBeneficiary(UUID id) {
        return switch (accounts.getAccount(id)) {
            case RemoteResult.Success<AccountSummary> s -> {
                if ("CLOSED".equals(s.value().status())) {
                    throw new TransferRejectedException("BENEFICIARY_NOT_ACTIVE", 422,
                            "Beneficiary account cannot receive funds");
                }
                yield s.value();
            }
            case RemoteResult.Rejected<AccountSummary> r when r.httpStatus() == 404 ->
                    throw new TransferRejectedException("BENEFICIARY_NOT_FOUND", 422, "Beneficiary account not found");
            case RemoteResult.Rejected<AccountSummary> r ->
                    throw new TransferRejectedException(r.code(), 422, "Beneficiary account check failed");
            case RemoteResult.Unknown<AccountSummary> u -> throw new DependencyUnavailableException();
        };
    }

    /** SHA-256 over the normalized payload; detects re-use of a key for a different request. */
    static String requestHash(CreateTransferRequest r, BigDecimal2 amount) {
        String canonical = String.join("|", r.sourceAccountId().toString(), r.beneficiaryAccountId().toString(),
                amount.value().toPlainString(), r.currency() == null ? "" : r.currency(),
                r.description() == null ? "" : r.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Amount normalized to 2 decimals so "10" and "10.00" are the same request. */
    record BigDecimal2(java.math.BigDecimal value) {
        static BigDecimal2 of(CreateTransferRequest r) {
            return new BigDecimal2(Money.of(r.amount()).amount());
        }
    }
}
