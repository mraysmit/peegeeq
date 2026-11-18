package dev.mars.peegeeq.failover;

/**
 * A cluster-settings operation failed: the settings could not be read, or a write was not stored (plan §9.34,
 * contract 3). No caller may treat this result as stored settings or as an absent record.
 */
public final class PgClusterConfigurationException extends RuntimeException {
    public PgClusterConfigurationException(String message) { super(message); }
    public PgClusterConfigurationException(String message, Throwable cause) { super(message, cause); }
}
