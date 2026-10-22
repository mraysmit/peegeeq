package dev.mars.peegeeq.failover;

/** Synchronous durability could not be observed, confirmed, or installed. Admission stays closed. */
public final class PgDurabilityException extends RuntimeException {
    public PgDurabilityException(String message) { super(message); }
    public PgDurabilityException(String message, Throwable cause) { super(message, cause); }
}
