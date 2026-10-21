package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Wraps a real coordinator. It records when each renewal starts and lets a test replace or hold
 * the renewal or update call. Every other call, and every call without a hook, reaches the real
 * coordinator unchanged.
 */
public final class ObservedLeaseCoordinator implements PgLeaseCoordinator {
    private final PgLeaseCoordinator delegate;
    private final List<Long> renewStarts = new CopyOnWriteArrayList<>();
    private volatile Function<PgControlRecord, Future<PgControlRecord>> renewHook;
    private volatile BiFunction<PgControlRecord, JsonObject, Future<PgControlRecord>> updateHook;

    public ObservedLeaseCoordinator(PgLeaseCoordinator delegate) {
        this.delegate = delegate;
    }

    /** Replaces the renewal call. The hook receives the record the elector asks to renew. */
    public void onRenew(Function<PgControlRecord, Future<PgControlRecord>> hook) {
        renewHook = hook;
    }

    /** Replaces the update call. Its hook may call {@link #realUpdate} after a condition is met. */
    public void onUpdate(BiFunction<PgControlRecord, JsonObject, Future<PgControlRecord>> hook) {
        updateHook = hook;
    }

    public void clearHooks() {
        renewHook = null;
        updateHook = null;
    }

    /** Nanosecond timestamps of every renewal call, in call order. */
    public List<Long> renewStarts() {
        return List.copyOf(renewStarts);
    }

    public Future<PgControlRecord> realRenew(PgControlRecord held) {
        return delegate.renew(held);
    }

    public Future<PgControlRecord> realUpdate(PgControlRecord held, JsonObject intent) {
        return delegate.update(held, intent);
    }

    @Override public Future<PgControlRecord> acquireInitial(JsonObject intent) {
        return delegate.acquireInitial(intent);
    }

    @Override public Future<PgControlRecord> acquireAfterRelease(PgControlRecord released, JsonObject intent) {
        return delegate.acquireAfterRelease(released, intent);
    }

    @Override public Future<Optional<PgControlRecord>> read() {
        return delegate.read();
    }

    @Override public Future<PgControlRecord> renew(PgControlRecord held) {
        renewStarts.add(System.nanoTime());
        var hook = renewHook;
        return hook == null ? delegate.renew(held) : hook.apply(held);
    }

    @Override public Future<PgControlRecord> update(PgControlRecord held, JsonObject intent) {
        var hook = updateHook;
        return hook == null ? delegate.update(held, intent) : hook.apply(held, intent);
    }

    @Override public Future<PgControlRecord> release(PgControlRecord held) {
        return delegate.release(held);
    }

    @Override public Future<Void> close() {
        return delegate.close();
    }
}
