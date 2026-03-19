package dev.mars.peegeeq.api.lifecycle;

import io.vertx.core.Future;

/**
 * Explicit close hook contract for optional modules to participate in PeeGeeQManager shutdown
 * without relying on reflection. Modules should register instances of this with the manager
 * via a LifecycleHookRegistrar when available.
 */
public interface PeeGeeQCloseHook {
    /** A short, human-readable name for logging. */
    String name();

    /** Reactive close. Must be non-blocking. Safe to call multiple times. */
    Future<Void> closeReactive();
}

