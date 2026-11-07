package com.securebank.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money value object")
class MoneyTest {

    @Test
    @DisplayName("normalizes scale to 2 using banker's rounding")
    void normalizesScale() {
        assertThat(Money.of("10.005").amount()).isEqualByComparingTo("10.00"); // HALF_EVEN -> down to even
        assertThat(Money.of("10.015").amount()).isEqualByComparingTo("10.02"); // HALF_EVEN -> up to even
        assertThat(Money.of(new BigDecimal("5")).amount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("adds and subtracts preserving precision")
    void addSubtract() {
        Money a = Money.of("100.10");
        Money b = Money.of("0.20");
        assertThat(a.add(b).amount()).isEqualByComparingTo("100.30");
        assertThat(a.subtract(b).amount()).isEqualByComparingTo("99.90");
    }

    @Test
    @DisplayName("classifies sign correctly")
    void signChecks() {
        assertThat(Money.of("1.00").isPositive()).isTrue();
        assertThat(Money.zero().isZero()).isTrue();
        assertThat(Money.of("-1.00").isNegative()).isTrue();
    }

    @Test
    @DisplayName("compares amounts for balance checks")
    void comparisons() {
        Money balance = Money.of("500.00");
        assertThat(balance.isGreaterThanOrEqualTo(Money.of("500.00"))).isTrue();
        assertThat(balance.isLessThan(Money.of("500.01"))).isTrue();
    }

    @Test
    @DisplayName("rejects mixing currencies")
    void rejectsCurrencyMismatch() {
        Money sgd = Money.of(new BigDecimal("1.00"), "SGD");
        Money usd = Money.of(new BigDecimal("1.00"), "USD");
        assertThatThrownBy(() -> sgd.add(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("equality is value-based regardless of trailing zeros")
    void valueEquality() {
        assertThat(Money.of("10.00")).isEqualTo(Money.of(new BigDecimal("10")));
    }
}
