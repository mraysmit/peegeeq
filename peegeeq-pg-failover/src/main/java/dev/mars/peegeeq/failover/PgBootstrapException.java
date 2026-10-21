package dev.mars.peegeeq.failover;

/** First-start bootstrap was refused. Nothing it refused may be treated as provisioning evidence. */
public final class PgBootstrapException extends RuntimeException {
    public PgBootstrapException(String message) { super(message); }
    public PgBootstrapException(String message, Throwable cause) { super(message, cause); }
}
