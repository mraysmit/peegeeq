package dev.mars.peegeeq.failover;

/** The local role could not be observed, a standby could not be promoted, or the checkpoint after promotion could not be confirmed. */
public class PgPromotionException extends RuntimeException {
    public PgPromotionException(String message) { super(message); }
    public PgPromotionException(String message, Throwable cause) { super(message, cause); }
}
