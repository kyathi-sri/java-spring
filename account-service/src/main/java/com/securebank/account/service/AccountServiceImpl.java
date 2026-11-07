package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountAudit;
import com.securebank.account.domain.AuditOperation;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.BalanceResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.exception.AccountNotFoundException;
import com.securebank.account.repository.AccountAuditRepository;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.security.Caller;
import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final AccountAuditRepository audits;

    public AccountServiceImpl(AccountRepository accounts, AccountAuditRepository audits) {
        this.accounts = accounts;
        this.audits = audits;
    }

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, Caller caller) {
        String currency = request.currency() != null ? request.currency() : Money.DEFAULT_CURRENCY;
        String ownerId = (caller.admin() && request.ownerId() != null && !request.ownerId().isBlank())
                ? request.ownerId() : caller.subject();

        Account account = new Account(generateUniqueAccountNumber(), ownerId,
                request.customerName(), currency);
        accounts.save(account);

        // Log identifiers only: never names, tokens or balances beyond what is needed.
        log.info("Account created id={} number={}", account.getId(), account.getAccountNumber());
        return AccountResponse.from(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id, Caller caller) {
        return AccountResponse.from(loadVisible(id, caller));
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID id, Caller caller) {
        return BalanceResponse.from(loadVisible(id, caller));
    }

    @Override
    @Transactional
    public BalanceResponse deposit(UUID id, AmountRequest request, Caller caller) {
        Account account = lockVisible(id, caller);
        Money amount = Money.of(request.amount(), account.getCurrency());

        account.credit(amount);
        recordAudit(account, AuditOperation.DEPOSIT, amount, request.reference(), caller);

        log.info("Deposit posted account={} amount={}", account.getAccountNumber(), amount.amount());
        return BalanceResponse.from(account);
    }

    @Override
    @Transactional
    public BalanceResponse withdraw(UUID id, AmountRequest request, Caller caller) {
        Account account = lockVisible(id, caller);
        Money amount = Money.of(request.amount(), account.getCurrency());

        account.debit(amount); // throws if inactive or insufficient funds => whole tx rolls back
        recordAudit(account, AuditOperation.WITHDRAWAL, amount, request.reference(), caller);

        log.info("Withdrawal posted account={} amount={}", account.getAccountNumber(), amount.amount());
        return BalanceResponse.from(account);
    }

    private void recordAudit(Account account, AuditOperation op, Money amount, String reference, Caller caller) {
        audits.save(new AccountAudit(account.getId(), op, amount.amount(), account.getBalance(),
                reference, CorrelationIdFilter.current(), caller.subject()));
    }

    private Account loadVisible(UUID id, Caller caller) {
        Account account = accounts.findById(id).orElseThrow(AccountNotFoundException::new);
        return assertVisible(account, caller);
    }

    /** Row-locks the account (SELECT ... FOR UPDATE) before a balance change. */
    private Account lockVisible(UUID id, Caller caller) {
        Account account = accounts.findByIdForUpdate(id).orElseThrow(AccountNotFoundException::new);
        return assertVisible(account, caller);
    }

    private Account assertVisible(Account account, Caller caller) {
        if (caller.canSeeAllAccounts() || account.getOwnerId().equals(caller.subject())) {
            return account;
        }
        // 404, not 403: do not reveal that the account exists.
        throw new AccountNotFoundException();
    }

    private String generateUniqueAccountNumber() {
        for (int i = 0; i < MAX_NUMBER_ATTEMPTS; i++) {
            String candidate = "SB" + String.format("%012d", Math.floorMod(RANDOM.nextLong(), 1_000_000_000_000L));
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        // The UNIQUE constraint remains the real guarantee; this only signals extreme bad luck.
        throw new IllegalStateException("Could not allocate a unique account number");
    }
}
