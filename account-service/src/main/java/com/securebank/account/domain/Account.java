package com.securebank.account.domain;

import com.securebank.account.exception.AccountNotActiveException;
import com.securebank.account.exception.InsufficientFundsException;
import com.securebank.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Account aggregate. Business rules (status checks, overdraft prevention) live here so
 * they cannot be bypassed by any caller.
 *
 * <p>Concurrency: balance changes are performed under a pessimistic row lock (see
 * {@code AccountRepository#findByIdForUpdate}); {@link #version} is an additional optimistic
 * guard, and the DB CHECK (balance >= 0) is the final safety net.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @Column(name = "owner_id", nullable = false, updatable = false, length = 64)
    private String ownerId;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /** Sum of HELD reservations. available = balance - reservedBalance. */
    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedBalance;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA
    }

    public Account(String accountNumber, String ownerId, String customerName, String currency) {
        this.id = UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.customerName = customerName;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balance = Money.zero(currency).amount();
        this.reservedBalance = Money.zero(currency).amount();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Credits are allowed on any non-closed account. */
    public void credit(Money amount) {
        if (status == AccountStatus.CLOSED) {
            throw new AccountNotActiveException(accountNumber, status);
        }
        this.balance = Money.of(balance, currency).add(amount).amount();
        this.updatedAt = Instant.now();
    }

    /**
     * Debits require an ACTIVE account and sufficient <em>available</em> funds: money already
     * reserved for an in-flight transfer cannot be withdrawn (prevents double spending).
     */
    public void debit(Money amount) {
        requireActiveWithAvailable(amount);
        this.balance = Money.of(balance, currency).subtract(amount).amount();
        this.updatedAt = Instant.now();
    }

    /** Reserve funds for a transfer. Same rules as a debit, but the balance is not reduced yet. */
    public void reserve(Money amount) {
        requireActiveWithAvailable(amount);
        this.reservedBalance = Money.of(reservedBalance, currency).add(amount).amount();
        this.updatedAt = Instant.now();
    }

    /** Return reserved funds to "available" (transfer failed / compensated). */
    public void releaseReservation(Money amount) {
        requireReserved(amount);
        this.reservedBalance = Money.of(reservedBalance, currency).subtract(amount).amount();
        this.updatedAt = Instant.now();
    }

    /**
     * Turn a reservation into a real debit. No status or funds check: the debit was authorized
     * when the funds were reserved, and the money has been ring-fenced since.
     */
    public void captureReservation(Money amount) {
        requireReserved(amount);
        this.reservedBalance = Money.of(reservedBalance, currency).subtract(amount).amount();
        this.balance = Money.of(balance, currency).subtract(amount).amount();
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAvailableBalance() {
        return available().amount();
    }

    private Money available() {
        return Money.of(balance, currency).subtract(Money.of(reservedBalance, currency));
    }

    private void requireActiveWithAvailable(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountNumber, status);
        }
        if (!available().isGreaterThanOrEqualTo(amount)) {
            throw new InsufficientFundsException(accountNumber);
        }
    }

    private void requireReserved(Money amount) {
        if (Money.of(reservedBalance, currency).isLessThan(amount)) {
            throw new IllegalStateException("Reserved balance of " + accountNumber + " is lower than " + amount);
        }
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getOwnerId() { return ownerId; }
    public String getCustomerName() { return customerName; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getReservedBalance() { return reservedBalance; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
