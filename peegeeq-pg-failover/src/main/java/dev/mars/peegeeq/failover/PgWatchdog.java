package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;

/**
 * Watchdog port. Once activated, the watchdog resets the host unless it receives a keepalive
 * within its timeout. Every failure is a {@link PgWatchdogException}. Whether the watchdog is
 * armed is derived at run time and never stored.
 */
public interface PgWatchdog {
    /** Arms the watchdog and returns its actual timeout. Fails when it cannot be armed or its timeout is unknown. */
    Future<Duration> activate();

    /** Restarts the timeout. Fails when the watchdog is not active or the keepalive was not delivered. */
    Future<Void> keepalive();

    /**
     * Disarms the watchdog. Succeeds when it is not active. A failure means it may still be
     * armed; the caller must keep feeding it or accept the reset.
     */
    Future<Void> disarm();
}
