package dev.mars.peegeeq.failover;

/** Ownership was not established. No caller may treat this result as writer permission. */
public final class PgLeaseProtocolException extends RuntimeException {
    public PgLeaseProtocolException(String message) { super(message); }
    public PgLeaseProtocolException(String message, Throwable cause) { super(message, cause); }
}
