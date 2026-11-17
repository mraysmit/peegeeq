package dev.mars.peegeeq.failover;

import io.vertx.core.Future;

/**
 * The local server's configuration (plan §9.33, contract 1): its observation and its reload. Observations are derived and
 * never stored. Every operation fails with {@link PgConfigurationException} when its result is not confirmed.
 */
public interface PgConfigurationControl {
    /** The postmaster start time, the server version, the role, and the parameters pending a restart. Fails while stopped. */
    Future<PgServerSettings> settings();

    /**
     * Reloads the configuration files and confirms that the reload happened (plan §9.33, contract 1). An error that makes
     * PostgreSQL reject a whole file (a syntax error, an unknown parameter, an invalid {@code pg_hba.conf} rule) fails
     * the call before any reload is sent. An invalid value of a known parameter can be told apart from a change that
     * waits for a restart only after the reload; it then fails the call, after PostgreSQL applied the other changes.
     */
    Future<Void> reload();
}
