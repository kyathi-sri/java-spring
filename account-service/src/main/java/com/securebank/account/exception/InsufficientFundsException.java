package com.securebank.account.exception;

import com.securebank.common.error.BusinessException;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(String accountNumber) {
        super("INSUFFICIENT_FUNDS", 422, "Insufficient funds in account " + accountNumber);
    }
}
