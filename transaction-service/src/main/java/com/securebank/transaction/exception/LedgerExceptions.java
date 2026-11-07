package com.securebank.transaction.exception;

import com.securebank.common.error.BusinessException;

import java.util.UUID;

/** Business failures raised by the ledger. Messages are safe to return to callers. */
public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    public static class EntryNotFoundException extends BusinessException {
        public EntryNotFoundException(UUID id) {
            super("ENTRY_NOT_FOUND", 404, "Journal entry " + id + " not found");
        }

        public EntryNotFoundException(String reference) {
            super("ENTRY_NOT_FOUND", 404, "No journal entry with reference " + reference);
        }
    }

    public static class UnbalancedEntryException extends BusinessException {
        public UnbalancedEntryException(String currency) {
            super("UNBALANCED_ENTRY", 422, "Debits and credits do not balance for currency " + currency);
        }
    }

    /** Same reference re-used with a different payload: refusing protects against double posting. */
    public static class IdempotencyConflictException extends BusinessException {
        public IdempotencyConflictException(String reference) {
            super("IDEMPOTENCY_CONFLICT", 409,
                    "Reference " + reference + " was already used for a different posting");
        }
    }

    public static class ReversalNotAllowedException extends BusinessException {
        public ReversalNotAllowedException(String message) {
            super("REVERSAL_NOT_ALLOWED", 422, message);
        }
    }

    public static class InvalidRequestException extends BusinessException {
        public InvalidRequestException(String message) {
            super("INVALID_REQUEST", 400, message);
        }
    }
}
