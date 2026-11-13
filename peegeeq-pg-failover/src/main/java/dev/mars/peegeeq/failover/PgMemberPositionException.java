package dev.mars.peegeeq.failover;

/**
 * A member-position operation failed: a publication was not stored, or the published positions could not be read
 * (plan §9.28, P7a). No caller may treat this result as a stored or an absent position.
 */
public final class PgMemberPositionException extends RuntimeException {
    public PgMemberPositionException(String message) { super(message); }
    public PgMemberPositionException(String message, Throwable cause) { super(message, cause); }
}
