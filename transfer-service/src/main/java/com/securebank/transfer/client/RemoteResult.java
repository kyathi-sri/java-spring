package com.securebank.transfer.client;

/**
 * Outcome of a state-changing call to another service. The saga never treats {@link Unknown} as a
 * failure: a timeout may hide a request that was applied, so the persisted state must be checked.
 */
public sealed interface RemoteResult<T> {

    /** 2xx: applied (or an idempotent replay of an earlier success). */
    record Success<T>(T value) implements RemoteResult<T> {
    }

    /** Definitive business rejection (4xx). The downstream service guarantees nothing was applied. */
    record Rejected<T>(int httpStatus, String code, String message) implements RemoteResult<T> {
    }

    /** Timeout, connection failure, 5xx, 401/403/408/429: the outcome is not known. */
    record Unknown<T>(String reason) implements RemoteResult<T> {
    }
}
