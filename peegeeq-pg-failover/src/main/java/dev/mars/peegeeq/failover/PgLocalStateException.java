package dev.mars.peegeeq.failover;

/** Node-local supervisor state could not be read, written, or changed. Admission stays closed. */
public final class PgLocalStateException extends RuntimeException {
    public PgLocalStateException(String message) { super(message); }
    public PgLocalStateException(String message, Throwable cause) { super(message, cause); }
}