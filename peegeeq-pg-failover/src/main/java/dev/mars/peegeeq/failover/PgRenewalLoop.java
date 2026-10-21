package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Calls {@link PgFailoverMonitor#renew()} on the HA interval for as long as this node holds the
 * writer lease. A renewal that fails has already withdrawn admission and stopped the local writer,
 * so the loop ends and its {@link #completion()} fails with that failure. The loop never starts
 * a second renewal while one is in flight, and it starts renewals at least one loop interval apart.
 *
 * <p>The loop only schedules. Ownership, freshness, and the demotion rule stay in the elector and
 * the monitor. Start the loop directly after acquiring the lease, before any start or promotion
 * effect, so that those effects cannot delay renewal.
 */
public final class PgRenewalLoop {
    private enum State { NEW, RUNNING, STOPPING, ENDED }

    private static final Logger logger = LoggerFactory.getLogger(PgRenewalLoop.class);
    private final Vertx vertx;
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgFailoverMonitor monitor;
    private final Promise<Void> completion = Promise.promise();
    private final Promise<Void> stopped = Promise.promise();
    private State state = State.NEW;
    private boolean inFlight;
    private long timerId;

    public PgRenewalLoop(Vertx vertx, PgNodeConfig config, PgPrimaryElector elector, PgFailoverMonitor monitor) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.config = Objects.requireNonNull(config, "config");
        this.elector = Objects.requireNonNull(elector, "elector");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /**
     * Starts the loop. Refused unless the elector holds fresh ownership and this loop has never
     * run. If the first timer cannot be scheduled the call fails and no renewal will occur, so
     * the caller must demote the node.
     */
    public synchronized Future<Void> start() {
        if (state != State.NEW) return refused("The loop was already started or stopped");
        if (!elector.hasFreshOwnership()) return refused("The loop requires fresh ownership of the writer lease");
        try {
            schedule(config.loopInterval().toMillis());
        } catch (RuntimeException failure) {
            logger.error("Renewal loop start failed on node {}", config.nodeId(), failure);
            state = State.ENDED;
            completion.tryFail(failure);
            stopped.tryComplete();
            return Future.failedFuture(failure);
        }
        state = State.RUNNING;
        return Future.succeededFuture();
    }

    /**
     * Succeeds when the loop is stopped. Fails with the renewal failure when ownership was not
     * renewed; by then the local writer has been demoted.
     */
    public Future<Void> completion() {
        return completion.future();
    }

    /**
     * Cancels the next renewal and waits for one in flight. Ownership and the local writer are
     * not changed. Idempotent. Fails only when the renewal in flight fails.
     */
    public synchronized Future<Void> stop() {
        if (state == State.NEW) {
            state = State.ENDED;
            completion.tryComplete();
            stopped.tryComplete();
        } else if (state == State.RUNNING) {
            vertx.cancelTimer(timerId);
            if (inFlight) {
                state = State.STOPPING;
            } else {
                state = State.ENDED;
                completion.tryComplete();
                stopped.tryComplete();
            }
        }
        return stopped.future();
    }

    private void tick() {
        long started;
        synchronized (this) {
            if (state != State.RUNNING) return;
            inFlight = true;
            started = System.nanoTime();
        }
        Future<PgControlRecord> renewal;
        try {
            renewal = monitor.renew();
        } catch (RuntimeException failure) {
            renewal = Future.failedFuture(failure);
        }
        renewal.onSuccess(renewed -> renewalSucceeded(started)).onFailure(this::renewalFailed);
    }

    private void renewalSucceeded(long started) {
        RuntimeException schedulingFailure = null;
        synchronized (this) {
            inFlight = false;
            if (state == State.STOPPING) {
                state = State.ENDED;
                completion.tryComplete();
                stopped.tryComplete();
                return;
            }
            if (state != State.RUNNING) return;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            try {
                schedule(config.loopInterval().toMillis() - elapsed);
            } catch (RuntimeException failure) {
                schedulingFailure = failure;
            }
        }
        if (schedulingFailure != null) {
            RuntimeException cause = schedulingFailure;
            // With no next renewal, ownership lapses. The writer must stop before it does.
            monitor.demote(null, "Renewal timer could not be scheduled").transform(demotion -> {
                if (demotion.failed()) cause.addSuppressed(demotion.cause());
                ended(cause);
                return Future.<Void>succeededFuture();
            }).onFailure(unexpected ->
                logger.error("Renewal loop could not record its end on node {}", config.nodeId(), unexpected));
        }
    }

    private void renewalFailed(Throwable failure) {
        ended(failure);
    }

    private void ended(Throwable failure) {
        boolean stopRequested;
        synchronized (this) {
            inFlight = false;
            if (state == State.ENDED) return;
            stopRequested = state == State.STOPPING;
            state = State.ENDED;
            vertx.cancelTimer(timerId);
        }
        logger.error("Renewal loop ended on node {}", config.nodeId(), failure);
        completion.tryFail(failure);
        if (stopRequested) {
            stopped.tryFail(failure);
        } else {
            stopped.tryComplete();
        }
    }

    private void schedule(long delayMillis) {
        timerId = vertx.setTimer(Math.max(1, delayMillis), id -> tick());
    }

    private Future<Void> refused(String reason) {
        PgLeaseProtocolException refusal = new PgLeaseProtocolException(reason);
        logger.error("Renewal loop start refused on node {}", config.nodeId(), refusal);
        return Future.failedFuture(refusal);
    }
}
