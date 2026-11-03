package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.Objects;

/**
 * Takeover steps that run on the new writer after it holds the lease. This increment has one: the
 * policy cutover after promotion.
 */
public final class PgTakeover {
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgLocalStateStore store;
    private final PgFailoverMonitor monitor;

    public PgTakeover(PgNodeConfig config, PgPrimaryElector elector, PgLocalStateStore store,
                      PgFailoverMonitor monitor) {
        this.config = Objects.requireNonNull(config, "config");
        this.elector = Objects.requireNonNull(elector, "elector");
        this.store = Objects.requireNonNull(store, "store");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /**
     * Cuts the durability policy over to the standbys that remain after this node's promotion, and
     * serves. The record is the takeover record that {@link PgFailoverMonitor#promoteStandby} accepted.
     * Returns the serving record. Plan §9.23 states the contract.
     */
    public Future<PgControlRecord> cutover(PgControlRecord held, PgFailoverMode mode) {
        return Future.failedFuture(new UnsupportedOperationException("not implemented"));
    }
}
