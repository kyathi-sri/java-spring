package com.securebank.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value object.
 *
 * <p>Banking money handling rules enforced here:
 * <ul>
 *   <li>Always backed by {@link BigDecimal} — never {@code double}/{@code float},
 *       which cannot represent decimal fractions exactly and corrupt ledgers.</li>
 *   <li>Scale is fixed to 2 decimal places using {@link RoundingMode#HALF_EVEN}
 *       (banker's rounding), the standard for financial rounding because it
 *       removes the upward bias of HALF_UP over many operations.</li>
 *   <li>Value objects are immutable: every operation returns a new instance.</li>
 * </ul>
 *
 * <p>This class intentionally models an <em>amount</em> only. Currency is kept as a
 * simple ISO code string to stay dependency-free in the shared library; services
 * that need richer currency logic can layer it on top.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final String DEFAULT_CURRENCY = "SGD";

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        this.amount = amount.setScale(SCALE, ROUNDING);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount) {
        return of(amount, DEFAULT_CURRENCY);
    }

    public static Money of(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        return new Money(amount, currency);
    }

    public static Money of(String amount) {
        return of(new BigDecimal(amount), DEFAULT_CURRENCY);
    }

    public static Money zero() {
        return of(BigDecimal.ZERO);
    }

    public static Money zero(String currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }

    @Override
    public int compareTo(Money o) {
        assertSameCurrency(o);
        return this.amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0 && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(currency, amount.toPlainString());
    }
}
