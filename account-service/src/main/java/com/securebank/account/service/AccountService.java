package com.securebank.account.service;

import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.BalanceResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.security.Caller;

import java.util.UUID;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request, Caller caller);

    AccountResponse getAccount(UUID id, Caller caller);

    BalanceResponse getBalance(UUID id, Caller caller);

    BalanceResponse deposit(UUID id, AmountRequest request, Caller caller);

    BalanceResponse withdraw(UUID id, AmountRequest request, Caller caller);
}
