package com.securebank.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error payload returned by every SecureBank service.
 *
 * <p>Keeping a single shape across services means clients (and the gateway) can
 * parse errors uniformly. Fields follow a problem-detail style:
 * <ul>
 *   <li>{@code timestamp} — when the error was produced (UTC).</li>
 *   <li>{@code status} — HTTP status code.</li>
 *   <li>{@code error} — short machine code, e.g. {@code ACCOUNT_NOT_FOUND}.</li>
 *   <li>{@code message} — human-readable, SAFE message (never leaks internals).</li>
 *   <li>{@code path} — request path.</li>
 *   <li>{@code correlationId} — request correlation id for log tracing.</li>
 *   <li>{@code fieldErrors} — optional per-field validation failures.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors
) {

    public record FieldViolation(String field, String message) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Instant timestamp = Instant.now();
        private int status;
        private String error;
        private String message;
        private String path;
        private String correlationId;
        private List<FieldViolation> fieldErrors;

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder fieldErrors(List<FieldViolation> fieldErrors) {
            this.fieldErrors = fieldErrors;
            return this;
        }

        public ApiError build() {
            return new ApiError(timestamp, status, error, message, path, correlationId, fieldErrors);
        }
    }
}
