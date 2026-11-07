package com.securebank.account.exception;

import com.securebank.common.error.BusinessException;

/** Failures of the internal hold/credit API. Every one of them means "not applied". */
public final class TransferSupportExceptions {

    private TransferSupportExceptions() {
    }

    public static class HoldNotFoundException extends BusinessException {
        public HoldNotFoundException(String reference) {
            super("HOLD_NOT_FOUND", 404, "Hold " + reference + " not found");
        }
    }

    public static class CreditNotFoundException extends BusinessException {
        public CreditNotFoundException(String reference) {
            super("CREDIT_NOT_FOUND", 404, "Credit " + reference + " not found");
        }
    }

    /** e.g. capture of a RELEASED hold, release of a CAPTURED hold, reserve of a released reference. */
    public static class HoldStateConflictException extends BusinessException {
        public HoldStateConflictException(String reference, String detail) {
            super("HOLD_STATE_CONFLICT", 409, "Hold " + reference + " " + detail);
        }
    }

    /** Reference already used for a different account or amount. */
    public static class IdempotencyConflictException extends BusinessException {
        public IdempotencyConflictException(String reference) {
            super("IDEMPOTENCY_CONFLICT", 409, "Reference " + reference + " was already used with different details");
        }
    }

    public static class CurrencyMismatchException extends BusinessException {
        public CurrencyMismatchException(String expected, String actual) {
            super("CURRENCY_MISMATCH", 422, "Account currency is " + expected + ", request currency is " + actual);
        }
    }
}
