package dev.mars.peegeeq.failover;

/**
 * The local server's configuration could not be observed or reloaded, or a configuration file holds an error (plan
 * §9.33, contract 1). No caller may treat this result as a reload that took effect.
 */
public final class PgConfigurationException extends RuntimeException {
    public PgConfigurationException(String message) { super(message); }
    public PgConfigurationException(String message, Throwable cause) { super(message, cause); }
}
