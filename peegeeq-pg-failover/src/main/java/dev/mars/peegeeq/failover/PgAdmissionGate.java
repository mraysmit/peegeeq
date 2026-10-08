package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;

/**
 * Local admission gate between the proxy and PostgreSQL. It covers every application SQL and
 * LISTEN route. It is not the takeover fence: writer exclusion is the local stop.
 */
public interface PgAdmissionGate {
    /**
     * Blocks new application connections, ends the existing ones, and observes that none remain,
     * within the budget. With PostgreSQL stopped it leaves the gate closed for the next start.
     */
    Future<Void> close(Duration budget);

    /** Admits application connections. PostgreSQL must be running. */
    Future<Void> open();
}