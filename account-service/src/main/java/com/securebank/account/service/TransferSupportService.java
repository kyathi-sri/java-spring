package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountAudit;
import com.securebank.account.domain.AccountCredit;
import com.securebank.account.domain.AccountHold;
import com.securebank.account.domain.AuditOperation;
import com.securebank.account.domain.HoldStatus;
import com.securebank.account.dto.internal.InternalDtos.AccountSummary;
import com.securebank.account.dto.internal.InternalDtos.CreditRequest;
import com.securebank.account.dto.internal.InternalDtos.CreditResponse;
import com.securebank.account.dto.internal.InternalDtos.HoldRequest;
import com.securebank.account.dto.internal.InternalDtos.HoldResponse;
import com.securebank.account.exception.AccountNotFoundException;
import com.securebank.account.exception.TransferSupportExceptions.CreditNotFoundException;
import com.securebank.account.exception.TransferSupportExceptions.CurrencyMismatchException;
import com.securebank.account.exception.TransferSupportExceptions.HoldNotFoundException;
import com.securebank.account.exception.TransferSupportExceptions.HoldStateConflictException;
import com.securebank.account.exception.TransferSupportExceptions.IdempotencyConflictException;
import com.securebank.account.repository.AccountAuditRepository;
import com.securebank.account.repository.AccountCreditRepository;
import com.securebank.account.repository.AccountHoldRepository;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.security.Caller;
import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Hold / capture / release / credit operations used by the transfer saga.
 *
 * <p>Rules that make every operation safe to retry:
 * <ol>
 *   <li>Lock the account row first ({@code SELECT ... FOR UPDATE}); all hold and balance changes for
 *       an account are therefore serialized, and the lock order (account, then hold) never varies.</li>
 *   <li>Look up the reference <em>before</em> checking business rules, so a retry of an operation that
 *       already succeeded returns the original result ({@code replayed=true}) instead of an error.</li>
 *   <li>Any error response therefore means "this was not applied", which lets the caller
 *       compensate safely.</li>
 * </ol>
 */
@Service
public class TransferSupportService {

    private static final Logger log = LoggerFactory.getLogger(TransferSupportService.class);

    public record Result<T>(T body, boolean created) {
    }

    private final AccountRepository accounts;
    private final AccountHoldRepository holds;
    private final AccountCreditRepository credits;
    private final AccountAuditRepository audits;

    public TransferSupportService(AccountRepository accounts, AccountHoldRepository holds,
                                  AccountCreditRepository credits, AccountAuditRepository audits) {
        this.accounts = accounts;
        this.holds = holds;
        this.credits = credits;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public AccountSummary summary(UUID accountId) {
        return accounts.findById(accountId).map(AccountSummary::from).orElseThrow(AccountNotFoundException::new);
    }

    // ------------------------------------------------------------------ holds

    @Transactional
    public Result<HoldResponse> placeHold(UUID accountId, HoldRequest request, Caller caller) {
        Account account = lock(accountId);

        Optional<AccountHold> existing = holds.findByReference(request.reference());
        if (existing.isPresent()) {
            AccountHold hold = existing.get();
            if (!hold.getAccountId().equals(accountId)) {
                throw new IdempotencyConflictException(request.reference());
            }
            if (hold.getStatus() == HoldStatus.RELEASED) {
                // Includes tombstones: a release already won, so this (possibly delayed) reserve is refused.
                throw new HoldStateConflictException(request.reference(), "has already been released");
            }
            if (hold.getAmount().compareTo(request.amount()) != 0 || !hold.getCurrency().equals(request.currency())) {
                throw new IdempotencyConflictException(request.reference());
            }
            return new Result<>(HoldResponse.from(hold, true), false);
        }

        requireCurrency(account, request.currency());
        Money amount = Money.of(request.amount(), account.getCurrency());
        account.reserve(amount); // ACTIVE + available funds check, under the row lock

        AccountHold hold = holds.save(AccountHold.held(request.reference(), accountId, amount.amount(),
                account.getCurrency(), caller.subject()));
        log.info("Hold placed reference={} account={} amount={}", hold.getReference(),
                account.getAccountNumber(), amount.amount());
        return new Result<>(HoldResponse.from(hold, false), true);
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(UUID accountId, String reference) {
        return holds.findByReference(reference)
                .filter(h -> h.getAccountId().equals(accountId))
                .map(h -> HoldResponse.from(h, false))
                .orElseThrow(() -> new HoldNotFoundException(reference));
    }

    @Transactional
    public HoldResponse captureHold(UUID accountId, String reference, Caller caller) {
        Account account = lock(accountId);
        AccountHold hold = holds.findByReference(reference)
                .filter(h -> h.getAccountId().equals(accountId))
                .orElseThrow(() -> new HoldNotFoundException(reference));

        return switch (hold.getStatus()) {
            case CAPTURED -> HoldResponse.from(hold, true);
            case RELEASED -> throw new HoldStateConflictException(reference, "was released and cannot be captured");
            case HELD -> {
                Money amount = Money.of(hold.getAmount(), account.getCurrency());
                account.captureReservation(amount);
                hold.capture();
                audit(account, AuditOperation.TRANSFER_DEBIT, amount, reference, caller);
                log.info("Hold captured reference={} account={}", reference, account.getAccountNumber());
                yield HoldResponse.from(hold, false);
            }
        };
    }

    /**
     * Releasing an unknown reference writes a tombstone. This closes the race where a reserve
     * request is still in flight when the caller gives up on it.
     */
    @Transactional
    public HoldResponse releaseHold(UUID accountId, String reference, Caller caller) {
        Account account = lock(accountId);
        Optional<AccountHold> existing = holds.findByReference(reference);

        if (existing.isEmpty()) {
            AccountHold tombstone = holds.save(AccountHold.tombstone(reference, accountId,
                    account.getCurrency(), caller.subject()));
            log.info("Release for unknown hold reference={}; tombstone written", reference);
            return HoldResponse.from(tombstone, false);
        }

        AccountHold hold = existing.get();
        if (!hold.getAccountId().equals(accountId)) {
            throw new IdempotencyConflictException(reference);
        }
        return switch (hold.getStatus()) {
            case RELEASED -> HoldResponse.from(hold, true);
            case CAPTURED -> throw new HoldStateConflictException(reference, "was captured and cannot be released");
            case HELD -> {
                account.releaseReservation(Money.of(hold.getAmount(), account.getCurrency()));
                hold.release();
                log.info("Hold released reference={} account={}", reference, account.getAccountNumber());
                yield HoldResponse.from(hold, false);
            }
        };
    }

    // ------------------------------------------------------------------ credits

    @Transactional
    public Result<CreditResponse> credit(UUID accountId, CreditRequest request, Caller caller) {
        Account account = lock(accountId);

        Optional<AccountCredit> existing = credits.findByReference(request.reference());
        if (existing.isPresent()) {
            AccountCredit c = existing.get();
            if (!c.getAccountId().equals(accountId) || c.getAmount().compareTo(request.amount()) != 0
                    || !c.getCurrency().equals(request.currency())) {
                throw new IdempotencyConflictException(request.reference());
            }
            return new Result<>(CreditResponse.from(c, true), false);
        }

        requireCurrency(account, request.currency());
        Money amount = Money.of(request.amount(), account.getCurrency());
        account.credit(amount); // refused on CLOSED accounts

        AccountCredit credit = credits.save(new AccountCredit(request.reference(), accountId, amount.amount(),
                account.getCurrency(), caller.subject()));
        audit(account, AuditOperation.TRANSFER_CREDIT, amount, request.reference(), caller);
        log.info("Transfer credit applied reference={} account={}", request.reference(), account.getAccountNumber());
        return new Result<>(CreditResponse.from(credit, false), true);
    }

    @Transactional(readOnly = true)
    public CreditResponse getCredit(UUID accountId, String reference) {
        return credits.findByReference(reference)
                .filter(c -> c.getAccountId().equals(accountId))
                .map(c -> CreditResponse.from(c, false))
                .orElseThrow(() -> new CreditNotFoundException(reference));
    }

    // ------------------------------------------------------------------ helpers

    private Account lock(UUID accountId) {
        return accounts.findByIdForUpdate(accountId).orElseThrow(AccountNotFoundException::new);
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(account.getCurrency(), currency);
        }
    }

    private void audit(Account account, AuditOperation op, Money amount, String reference, Caller caller) {
        audits.save(new AccountAudit(account.getId(), op, amount.amount(), account.getBalance(),
                reference, CorrelationIdFilter.current(), caller.subject()));
    }
}
