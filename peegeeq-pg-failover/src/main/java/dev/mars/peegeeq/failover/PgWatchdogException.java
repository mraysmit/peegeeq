package dev.mars.peegeeq.failover;

/** A watchdog operation failed. The watchdog's armed state must not be assumed from it. */
public final class PgWatchdogException extends RuntimeException {
    public PgWatchdogException(String message) { super(message); }
    public PgWatchdogException(String message, Throwable cause) { super(message, cause); }
}
