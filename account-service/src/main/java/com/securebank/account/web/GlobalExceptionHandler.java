package com.securebank.account.web;

import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.common.error.ApiError;
import com.securebank.common.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** One place that turns exceptions into safe, uniform {@link ApiError} responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest req) {
        log.warn("Business rule failed code={}", ex.getErrorCode());
        return build(ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(400, "VALIDATION_FAILED", "Request validation failed", req, violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest req) {
        return build(400, "MALFORMED_REQUEST", "Malformed request", req, null);
    }

    /** e.g. two requests racing to use the same hold/credit reference on different accounts. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Integrity constraint rejected a write");
        return build(409, "DUPLICATE_REFERENCE", "The request conflicts with an existing record", req, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> conflict(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(409, "CONCURRENT_MODIFICATION", "The resource was modified concurrently, please retry", req, null);
    }

    /** Last resort: log the detail server-side, return nothing internal to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error", ex);
        return build(500, "INTERNAL_ERROR", "An unexpected error occurred", req, null);
    }

    private ResponseEntity<ApiError> build(int status, String code, String message,
                                           HttpServletRequest req, List<ApiError.FieldViolation> fields) {
        ApiError body = ApiError.builder()
                .status(status)
                .error(code)
                .message(message)
                .path(req.getRequestURI())
                .correlationId(CorrelationIdFilter.current())
                .fieldErrors(fields)
                .build();
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(body);
    }
}
