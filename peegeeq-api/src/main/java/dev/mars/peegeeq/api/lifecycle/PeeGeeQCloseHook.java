package dev.mars.peegeeq.api.lifecycle;

import io.vertx.core.Future;

import java.util.concurrent.CompletableFuture;

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

    /** CompletableFuture variant for non-Vert.x consumers. */
    default CompletableFuture<Void> closeAsync() {
        return closeReactive().toCompletionStage().toCompletableFuture();
    }
}

