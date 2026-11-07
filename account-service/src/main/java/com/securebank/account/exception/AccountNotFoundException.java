package com.securebank.account.exception;

import com.securebank.common.error.BusinessException;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException() {
        // Deliberately generic: also used when a customer asks for someone else's account.
        super("ACCOUNT_NOT_FOUND", 404, "Account not found");
    }
}
