package com.securebank.transfer.exception;

import com.securebank.common.error.BusinessException;

/** Request-level rejections. None of them creates a transfer or moves money. */
public final class TransferExceptions {

    private TransferExceptions() {
    }

    public static class InvalidTransferException extends BusinessException {
        public InvalidTransferException(String code, String message) {
            super(code, 400, message);
        }
    }

    /** Unknown source account, or one the caller does not own (indistinguishable on purpose). */
    public static class SourceAccountNotFoundException extends BusinessException {
        public SourceAccountNotFoundException() {
            super("ACCOUNT_NOT_FOUND", 404, "Source account not found");
        }
    }

    public static class TransferRejectedException extends BusinessException {
        public TransferRejectedException(String code, int status, String message) {
            super(code, status, message);
        }
    }

    public static class IdempotencyConflictException extends BusinessException {
        public IdempotencyConflictException() {
            super("IDEMPOTENCY_CONFLICT", 409,
                    "This Idempotency-Key was already used for a different transfer request");
        }
    }

    public static class TransferNotFoundException extends BusinessException {
        public TransferNotFoundException(String reference) {
            super("TRANSFER_NOT_FOUND", 404, "Transfer " + reference + " not found");
        }
    }

    /** A dependency could not be reached while validating; nothing was created, retrying is safe. */
    public static class DependencyUnavailableException extends BusinessException {
        public DependencyUnavailableException() {
            super("DEPENDENCY_UNAVAILABLE", 503,
                    "A required service is temporarily unavailable; retry with the same Idempotency-Key");
        }
    }
}
