package dev.mars.peegeeq.failover;

/**
 * Durable result of a local action. {@link #PENDING} and {@link #UNKNOWN} require local
 * observation. {@link #COMPLETED} and {@link #REJECTED} are final.
 */
public enum PgActionResult {
    PENDING, UNKNOWN, COMPLETED, REJECTED;

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED;
    }
}