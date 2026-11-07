package com.securebank.common.error;

/**
 * Base class for expected, recoverable business-rule failures (e.g. insufficient
 * funds, inactive account). These map to 4xx responses, not 500s.
 *
 * <p>Each concrete exception carries a stable machine {@code errorCode} so clients
 * can branch on it, plus a {@code httpStatus} the global handler uses directly.
 * Messages must be safe to return to callers — never embed secrets or raw SQL.
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected BusinessException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
