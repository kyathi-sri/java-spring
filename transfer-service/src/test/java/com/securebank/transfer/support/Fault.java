package com.securebank.transfer.support;

/** Failure modes injected into fake downstream services. */
public enum Fault {
    /** The request is applied, but the caller sees a timeout (the dangerous case). */
    TIMEOUT_APPLIED,
    /** The request never reaches the service; the caller sees a timeout. */
    TIMEOUT_NOT_APPLIED,
    /** The request is applied, then the calling JVM "dies" before it can record the result. */
    CRASH_AFTER_APPLY,
    /** Definitive business rejection (the operation is not applied). */
    REJECT
}
