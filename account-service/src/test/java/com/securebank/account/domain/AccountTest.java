package com.securebank.account.domain;

import com.securebank.account.exception.AccountNotActiveException;
import com.securebank.account.exception.InsufficientFundsException;
import com.securebank.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Account domain rules")
class AccountTest {

    private Account newAccount() {
        return new Account("SB000000000001", "cust-1", "Alice Tan", "SGD");
    }

    @Test
    @DisplayName("starts ACTIVE with zero balance")
    void startsActive() {
        Account a = newAccount();
        assertThat(a.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(a.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("credit then debit updates balance exactly")
    void creditDebit() {
        Account a = newAccount();
        a.credit(Money.of("100.10"));
        a.debit(Money.of("0.20"));
        assertThat(a.getBalance()).isEqualByComparingTo("99.90");
    }

    @Test
    @DisplayName("withdrawing exactly the balance is allowed")
    void debitFullBalance() {
        Account a = newAccount();
        a.credit(Money.of("50.00"));
        a.debit(Money.of("50.00"));
        assertThat(a.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("rejects overdraft and leaves balance untouched")
    void rejectsOverdraft() {
        Account a = newAccount();
        a.credit(Money.of("10.00"));
        assertThatThrownBy(() -> a.debit(Money.of("10.01"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(a.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("blocks debits from non-ACTIVE accounts")
    void blocksDebitWhenInactive() {
        Account a = newAccount();
        a.credit(Money.of("10.00"));
        a.changeStatus(AccountStatus.INACTIVE);
        assertThatThrownBy(() -> a.debit(Money.of("1.00"))).isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    @DisplayName("allows credits to INACTIVE but not CLOSED accounts")
    void creditRules() {
        Account a = newAccount();
        a.changeStatus(AccountStatus.INACTIVE);
        a.credit(Money.of("5.00"));
        assertThat(a.getBalance()).isEqualByComparingTo("5.00");

        a.changeStatus(AccountStatus.CLOSED);
        assertThatThrownBy(() -> a.credit(Money.of("1.00"))).isInstanceOf(AccountNotActiveException.class);
    }
}
