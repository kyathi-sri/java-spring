package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountAudit;
import com.securebank.account.domain.AuditOperation;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.BalanceResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.exception.AccountNotFoundException;
import com.securebank.account.exception.InsufficientFundsException;
import com.securebank.account.repository.AccountAuditRepository;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.security.Caller;
import com.securebank.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountServiceImpl")
class AccountServiceImplTest {

    @Mock AccountRepository accounts;
    @Mock AccountAuditRepository audits;

    AccountServiceImpl service;

    final Caller alice = new Caller("cust-alice", false, false);
    final Caller bob = new Caller("cust-bob", false, false);
    final Caller support = new Caller("agent-1", false, true);
    final Caller admin = new Caller("admin-1", true, false);

    Account aliceAccount;

    @BeforeEach
    void setUp() {
        service = new AccountServiceImpl(accounts, audits);
        aliceAccount = new Account("SB000000000001", "cust-alice", "Alice Tan", "SGD");
        aliceAccount.credit(Money.of("100.00"));
    }

    @Test
    @DisplayName("creates an account owned by the caller, ignoring a customer-supplied ownerId")
    void createOwnedByCaller() {
        when(accounts.existsByAccountNumber(any())).thenReturn(false);

        var resp = service.createAccount(new CreateAccountRequest("Alice Tan", null, "someone-else"), alice);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("cust-alice");
        assertThat(resp.accountNumber()).startsWith("SB").hasSize(14);
        assertThat(resp.currency()).isEqualTo("SGD");
    }

    @Test
    @DisplayName("admin may open an account on behalf of a customer")
    void adminCreatesForCustomer() {
        when(accounts.existsByAccountNumber(any())).thenReturn(false);

        service.createAccount(new CreateAccountRequest("Bob Lim", "USD", "cust-bob"), admin);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("cust-bob");
        assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("withdraw debits balance and writes an audit record")
    void withdrawAudits() {
        when(accounts.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));

        BalanceResponse resp = service.withdraw(aliceAccount.getId(),
                new AmountRequest(new BigDecimal("30.00"), "ATM-1"), alice);

        assertThat(resp.availableBalance()).isEqualByComparingTo("70.00");
        ArgumentCaptor<AccountAudit> audit = ArgumentCaptor.forClass(AccountAudit.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().getOperation()).isEqualTo(AuditOperation.WITHDRAWAL);
        assertThat(audit.getValue().getBalanceAfter()).isEqualByComparingTo("70.00");
        assertThat(audit.getValue().getPerformedBy()).isEqualTo("cust-alice");
    }

    @Test
    @DisplayName("insufficient funds writes no audit record")
    void insufficientFunds() {
        when(accounts.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));

        assertThatThrownBy(() -> service.withdraw(aliceAccount.getId(),
                new AmountRequest(new BigDecimal("100.01"), null), alice))
                .isInstanceOf(InsufficientFundsException.class);

        verify(audits, never()).save(any());
        assertThat(aliceAccount.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("another customer gets 404 (not 403) for someone else's account")
    void ownershipHidden() {
        when(accounts.findById(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));

        assertThatThrownBy(() -> service.getAccount(aliceAccount.getId(), bob))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("support staff can read any account")
    void supportCanRead() {
        when(accounts.findById(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));

        assertThat(service.getBalance(aliceAccount.getId(), support).availableBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("unknown account yields not-found")
    void unknownAccount() {
        UUID id = UUID.randomUUID();
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deposit(id, new AmountRequest(BigDecimal.TEN, null), alice))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
