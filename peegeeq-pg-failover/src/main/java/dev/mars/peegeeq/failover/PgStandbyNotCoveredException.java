package dev.mars.peegeeq.failover;

/**
 * A required standby is not covered yet, in a way that can change by waiting: it is not connected,
 * it has several connections, it is not streaming, or its flush or replay position is behind the
 * boundary. An identity mismatch is a {@link PgDurabilityException} of the base type and does not
 * resolve by waiting.
 */
public final class PgStandbyNotCoveredException extends PgDurabilityException {
    public PgStandbyNotCoveredException(String message) { super(message); }
}
