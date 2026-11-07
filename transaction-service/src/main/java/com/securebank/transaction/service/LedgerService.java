package com.securebank.transaction.service;

import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.money.Money;
import com.securebank.common.web.PageResponse;
import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;
import com.securebank.transaction.domain.JournalEntry;
import com.securebank.transaction.domain.LedgerLine;
import com.securebank.transaction.dto.BalanceView;
import com.securebank.transaction.dto.EntryResponse;
import com.securebank.transaction.dto.LedgerLineView;
import com.securebank.transaction.dto.PostEntryRequest;
import com.securebank.transaction.dto.ReconciliationDtos.AccountCheckReport;
import com.securebank.transaction.dto.ReconciliationDtos.AccountCheckRequest;
import com.securebank.transaction.dto.ReconciliationDtos.AccountCheckResult;
import com.securebank.transaction.dto.ReconciliationDtos.CurrencyTotals;
import com.securebank.transaction.dto.ReconciliationDtos.TrialBalance;
import com.securebank.transaction.exception.LedgerExceptions.EntryNotFoundException;
import com.securebank.transaction.exception.LedgerExceptions.IdempotencyConflictException;
import com.securebank.transaction.exception.LedgerExceptions.InvalidRequestException;
import com.securebank.transaction.exception.LedgerExceptions.ReversalNotAllowedException;
import com.securebank.transaction.exception.LedgerExceptions.UnbalancedEntryException;
import com.securebank.transaction.repository.JournalEntryRepository;
import com.securebank.transaction.repository.LedgerLineRepository;
import com.securebank.transaction.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Double-entry ledger.
 *
 * <p>Transactions are managed with {@link TransactionTemplate} rather than {@code @Transactional}
 * because duplicate-reference races need a retry <em>after</em> the failed transaction has rolled
 * back: once a unique violation occurs, that transaction is unusable, so the "return the existing
 * entry" read must run in a fresh one.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final int MAX_PAGE_SIZE = 100;

    private final JournalEntryRepository entries;
    private final LedgerLineRepository lines;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public LedgerService(JournalEntryRepository entries, LedgerLineRepository lines,
                         PlatformTransactionManager txManager) {
        this.entries = entries;
        this.lines = lines;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ posting

    public EntryResponse post(PostEntryRequest request, Caller caller) {
        if (request.entryType() == EntryType.REVERSAL) {
            throw new InvalidRequestException("REVERSAL entries can only be created through the reversal endpoint");
        }
        assertBalanced(request.lines());

        try {
            return tx.execute(status -> {
                Optional<JournalEntry> existing = entries.findWithLinesByReference(request.reference());
                if (existing.isPresent()) {
                    return replay(existing.get(), request);
                }
                JournalEntry entry = new JournalEntry(request.reference(), request.entryType(),
                        request.description(), null, CorrelationIdFilter.current(), caller.subject());
                for (PostEntryRequest.Line l : request.lines()) {
                    entry.addLine(l.accountRef(), l.ownerId(), l.direction(),
                            normalize(l.amount(), l.currency()), l.currency());
                }
                entries.saveAndFlush(entry);
                log.info("Journal entry posted id={} reference={} type={}",
                        entry.getId(), entry.getReference(), entry.getEntryType());
                return EntryResponse.from(entry, false);
            });
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same reference committed first. Our transaction has
            // rolled back; read the winner in a new transaction and treat this call as a replay.
            return readTx.execute(status -> entries.findWithLinesByReference(request.reference())
                    .map(existing -> replay(existing, request))
                    .orElseThrow(() -> race));
        }
    }

    private EntryResponse replay(JournalEntry existing, PostEntryRequest request) {
        if (!samePayload(existing, request)) {
            throw new IdempotencyConflictException(request.reference());
        }
        log.info("Idempotent replay of reference={}", request.reference());
        return EntryResponse.from(existing, true);
    }

    // ------------------------------------------------------------------ reversal

    public EntryResponse reverse(UUID entryId, String reason, Caller caller) {
        try {
            return tx.execute(status -> {
                JournalEntry original = entries.findWithLinesById(entryId)
                        .orElseThrow(() -> new EntryNotFoundException(entryId));
                if (original.getEntryType() == EntryType.REVERSAL) {
                    throw new ReversalNotAllowedException(
                            "A reversal cannot itself be reversed; post an ADJUSTMENT instead");
                }
                Optional<JournalEntry> already = entries.findWithLinesByReversesEntryId(entryId);
                if (already.isPresent()) {
                    return EntryResponse.from(already.get(), true);
                }

                JournalEntry reversal = new JournalEntry("REV-" + original.getReference(), EntryType.REVERSAL,
                        "Reversal of " + original.getReference() + ": " + reason, original.getId(),
                        CorrelationIdFilter.current(), caller.subject());
                for (LedgerLine l : original.getLines()) {
                    reversal.addLine(l.getAccountRef(), l.getOwnerId(), l.getDirection().opposite(),
                            l.getAmount(), l.getCurrency());
                }
                entries.saveAndFlush(reversal);
                log.info("Journal entry {} reversed by {}", original.getId(), reversal.getId());
                return EntryResponse.from(reversal, false);
            });
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(reverses_entry_id) guarantees at most one reversal per entry.
            return readTx.execute(status -> entries.findWithLinesByReversesEntryId(entryId)
                    .map(existing -> EntryResponse.from(existing, true))
                    .orElseThrow(() -> race));
        }
    }

    // ------------------------------------------------------------------ reads

    /** Lets a caller whose posting timed out check whether it was actually applied. */
    public EntryResponse getEntryByReference(String reference) {
        return readTx.execute(status -> entries.findWithLinesByReference(reference)
                .map(e -> EntryResponse.from(e, false))
                .orElseThrow(() -> new EntryNotFoundException(reference)));
    }

    public EntryResponse getEntry(UUID entryId) {
        return readTx.execute(status -> entries.findWithLinesById(entryId)
                .map(e -> EntryResponse.from(e, false))
                .orElseThrow(() -> new EntryNotFoundException(entryId)));
    }

    public PageResponse<LedgerLineView> history(String accountRef, Instant from, Instant to,
                                                int page, int size, String sort, Caller caller) {
        Instant start = from != null ? from : Instant.EPOCH;
        Instant end = to != null ? to : END_OF_TIME;
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(direction, "postedAt").and(Sort.by(direction, "id")));

        return readTx.execute(status -> {
            Page<LedgerLineView> result = caller.canSeeAllAccounts()
                    ? lines.history(accountRef, start, end, pageable)
                    : lines.historyForOwner(accountRef, caller.subject(), start, end, pageable);
            return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements(), result.getTotalPages());
        });
    }

    /** Balance strictly before {@code asOf} (exclusive), i.e. the opening balance at that instant. */
    public BalanceView balance(String accountRef, Instant asOf, Caller caller) {
        Instant at = asOf != null ? asOf : Instant.now();
        BigDecimal balance = readTx.execute(status -> caller.canSeeAllAccounts()
                ? lines.balanceBefore(accountRef, at)
                : lines.balanceBeforeForOwner(accountRef, caller.subject(), at));
        return new BalanceView(accountRef, balance, at);
    }

    // ------------------------------------------------------------------ reconciliation

    public TrialBalance trialBalance() {
        return readTx.execute(status -> {
            List<CurrencyTotals> totals = lines.trialBalance().stream()
                    .map(r -> new CurrencyTotals(r.getCurrency(), r.getTotalDebits(), r.getTotalCredits(),
                            r.getLineCount(), r.getTotalDebits().compareTo(r.getTotalCredits()) == 0))
                    .toList();
            long unbalanced = lines.countUnbalancedEntries();
            boolean healthy = unbalanced == 0 && totals.stream().allMatch(CurrencyTotals::balanced);
            if (!healthy) {
                log.error("LEDGER INTEGRITY ALERT: trial balance failed, unbalancedEntries={}", unbalanced);
            }
            return new TrialBalance(Instant.now(), totals, unbalanced, healthy);
        });
    }

    /** Compares balances held by another system (e.g. account-service) with the ledger. */
    public AccountCheckReport checkAccounts(AccountCheckRequest request) {
        return readTx.execute(status -> {
            List<AccountCheckResult> results = request.items().stream().map(item -> {
                BigDecimal ledger = lines.balanceBefore(item.accountRef(), END_OF_TIME);
                BigDecimal difference = item.expectedBalance().subtract(ledger);
                return new AccountCheckResult(item.accountRef(), item.expectedBalance(), ledger,
                        difference, difference.signum() == 0);
            }).toList();
            int mismatched = (int) results.stream().filter(r -> !r.matched()).count();
            if (mismatched > 0) {
                log.warn("Reconciliation found {} mismatched account(s)", mismatched);
            }
            return new AccountCheckReport(Instant.now(), results.size(), mismatched, results);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Per currency, sum(DEBIT) must equal sum(CREDIT). Checked here and again by a DB trigger. */
    static void assertBalanced(List<PostEntryRequest.Line> requestLines) {
        Map<String, BigDecimal> netByCurrency = requestLines.stream().collect(Collectors.groupingBy(
                PostEntryRequest.Line::currency,
                Collectors.reducing(BigDecimal.ZERO,
                        l -> l.direction() == Direction.DEBIT ? l.amount() : l.amount().negate(),
                        BigDecimal::add)));
        netByCurrency.forEach((currency, net) -> {
            if (net.signum() != 0) {
                throw new UnbalancedEntryException(currency);
            }
        });
    }

    private static boolean samePayload(JournalEntry existing, PostEntryRequest request) {
        if (existing.getEntryType() != request.entryType()) {
            return false;
        }
        List<String> stored = existing.getLines().stream()
                .map(l -> key(l.getAccountRef(), l.getDirection(), l.getAmount(), l.getCurrency()))
                .sorted().toList();
        List<String> requested = request.lines().stream()
                .map(l -> key(l.accountRef(), l.direction(), normalize(l.amount(), l.currency()), l.currency()))
                .sorted().toList();
        return stored.equals(requested);
    }

    private static String key(String accountRef, Direction direction, BigDecimal amount, String currency) {
        return accountRef + "|" + direction + "|" + amount.toPlainString() + "|" + currency;
    }

    private static BigDecimal normalize(BigDecimal amount, String currency) {
        return Money.of(amount, currency).amount();
    }
}
