package dev.mars.peegeeq.failover;

/** A local process action failed or its effect was not confirmed. Never treat it as success. */
public final class PgProcessControlException extends RuntimeException {
    public PgProcessControlException(String message) { super(message); }
    public PgProcessControlException(String message, Throwable cause) { super(message, cause); }
}