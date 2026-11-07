package com.securebank.account.exception;

import com.securebank.account.domain.AccountStatus;
import com.securebank.common.error.BusinessException;

public class AccountNotActiveException extends BusinessException {

    public AccountNotActiveException(String accountNumber, AccountStatus status) {
        super("ACCOUNT_NOT_ACTIVE", 409,
                "Account %s is %s and cannot process this operation".formatted(accountNumber, status));
    }
}
