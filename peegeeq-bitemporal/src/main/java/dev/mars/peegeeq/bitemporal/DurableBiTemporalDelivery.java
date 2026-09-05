package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-flight delivery. LISTEN is a wake-up hint; periodic reconciliation covers lost hints. */
final class DurableBiTemporalDelivery<T> {
    private static final Logger logger = LoggerFactory.getLogger(DurableBiTemporalDelivery.class);
    private final Vertx vertx;
    private final DurableBiTemporalSubscriptionCoordinator coordinator;
    private final DurableBiTemporalSubscriptionCoordinator.SubscriptionKey key;
    private final EventStore<T> events;
    private final MessageHandler<BiTemporalEvent<T>> handler;
    private final int batchSize;
    private final Promise<Void> completion = Promise.promise();
    private Future<Void> current = Future.succeededFuture();
    private Future<Void> startup = Future.succeededFuture();
    private Future<Void> closing;
    private long timer = -1;
    private boolean running;
    private boolean dirty;
    private boolean suspended;
    private boolean stopped;

    DurableBiTemporalDelivery(Vertx vertx, DurableBiTemporalSubscriptionCoordinator coordinator,
            DurableBiTemporalSubscriptionCoordinator.SubscriptionKey key, EventStore<T> events,
            MessageHandler<BiTemporalEvent<T>> handler, int batchSize) {
        this.vertx = vertx;
        this.coordinator = coordinator;
        this.key = key;
        this.events = events;
        this.handler = handler;
        this.batchSize = batchSize;
    }

    synchronized Future<Void> start() {
        if (stopped) return Future.failedFuture(new IllegalStateException("Delivery is stopped"));
        startup = events.subscribe(null, message -> wake()).compose(v -> {
            synchronized (this) {
                if (stopped) return Future.failedFuture(new IllegalStateException("Delivery stopped during startup"));
                timer = vertx.setPeriodic(1000, id -> wake().onFailure(this::fail));
            }
            return wake();
        }).onFailure(this::fail);
        return startup;
    }

    synchronized Future<Void> wake() {
        if (stopped || suspended) return Future.succeededFuture();
        dirty = true;
        if (running) return current;
        running = true;
        dirty = false;
        Promise<Void> pass = Promise.promise();
        current = pass.future();
        coordinator.replay(key, events, handler, batchSize)
            .onSuccess(v -> {
                synchronized (this) {
                    running = false;
                    pass.complete();
                    if (dirty && !stopped && !suspended) wake().onFailure(this::fail);
                }
            }).onFailure(error -> {
                synchronized (this) {
                    running = false;
                    fail(error);
                    pass.fail(error);
                }
            });
        return pass.future();
    }

    synchronized Future<Void> suspend() {
        suspended = true;
        return current;
    }

    synchronized Future<Void> resume() {
        if (stopped) return Future.failedFuture(new IllegalStateException("Delivery stopped; register a new service handler"));
        suspended = false;
        return wake();
    }

    Future<Void> completion() { return completion.future(); }

    private synchronized void fail(Throwable error) {
        stopped = true;
        if (timer != -1) vertx.cancelTimer(timer);
        if (completion.tryFail(error)) logger.error("Durable delivery failed for {}", key, error);
    }

    synchronized Future<Void> close() {
        if (closing != null) return closing;
        stopped = true;
        if (timer != -1) vertx.cancelTimer(timer);
        // Delivery errors are logged and propagated through completion, independently of
        // whether resource cleanup succeeds. A failed handler must not prevent cleanup/restart.
        closing = Future.join(startup, current).transform(delivered -> {
            if (delivered.failed()) fail(delivered.cause());
            return events.close();
        });
        return closing.onSuccess(v -> completion.tryComplete()).onFailure(this::fail);
    }
}
