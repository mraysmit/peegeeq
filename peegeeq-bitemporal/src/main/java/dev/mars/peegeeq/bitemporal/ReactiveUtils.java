/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Utility methods for reactive operations in the bi-temporal event store.
 * 
 * This class provides bridge methods between Vert.x Future and CompletableFuture,
 * following the established patterns from peegeeq-outbox.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
public final class ReactiveUtils {
    private static final Logger logger = LoggerFactory.getLogger(ReactiveUtils.class);

    private ReactiveUtils() {
        // Utility class
    }

    /**
     * Executes a Future-returning operation on the Vert.x context.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * the proper execution context for Vert.x operations.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer.
     *
     * @param vertx The Vertx instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    public static <T> Future<T> executeOnVertxContext(Vertx vertx, Supplier<Future<T>> operation) {
        Context context = vertx.getOrCreateContext();
        if (context == Vertx.currentContext()) {
            // Already on Vert.x context, execute directly
            logger.trace("Already on Vert.x context, executing directly");
            return operation.get();
        } else {
            // Execute on Vert.x context using runOnContext
            logger.trace("Not on Vert.x context, switching to Vert.x context");
            Promise<T> promise = Promise.promise();
            context.runOnContext(v -> {
                operation.get()
                    .onSuccess(promise::complete)
                    .onFailure(promise::fail);
            });
            return promise.future();
        }
    }

    /**
     * Converts a Vert.x Future to a CompletableFuture.
     * This maintains API compatibility while using reactive operations internally.
     *
     * @param future The Vert.x Future to convert
     * @return CompletableFuture that completes when the Future completes
     */
    public static <T> CompletableFuture<T> toCompletableFuture(Future<T> future) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        
        future.onSuccess(result -> {
            logger.trace("Vert.x Future completed successfully");
            completableFuture.complete(result);
        }).onFailure(error -> {
            logger.trace("Vert.x Future failed: {}", error.getMessage());
            completableFuture.completeExceptionally(error);
        });
        
        return completableFuture;
    }

    /**
     * Converts a CompletableFuture to a Vert.x Future.
     * This allows integration with existing CompletableFuture-based code.
     *
     * @param completableFuture The CompletableFuture to convert
     * @return Future that completes when the CompletableFuture completes
     */
    public static <T> Future<T> fromCompletableFuture(CompletableFuture<T> completableFuture) {
        Promise<T> promise = Promise.promise();
        
        completableFuture.whenComplete((result, error) -> {
            if (error != null) {
                logger.trace("CompletableFuture failed: {}", error.getMessage());
                promise.fail(error);
            } else {
                logger.trace("CompletableFuture completed successfully");
                promise.complete(result);
            }
        });
        
        return promise.future();
    }

    /**
     * Executes a CompletableFuture-returning operation on the Vert.x context.
     * This is a convenience method that combines executeOnVertxContext with future conversion.
     *
     * @param vertx The Vertx instance
     * @param operation The operation to execute that returns a CompletableFuture
     * @return CompletableFuture that completes when the operation completes
     */
    public static <T> CompletableFuture<T> executeOnVertxContextAsync(Vertx vertx, Supplier<CompletableFuture<T>> operation) {
        return toCompletableFuture(
            executeOnVertxContext(vertx, () -> fromCompletableFuture(operation.get()))
        );
    }

    /**
     * Creates a failed CompletableFuture with the given error.
     * This is a utility method for consistent error handling.
     *
     * @param error The error to wrap
     * @return Failed CompletableFuture
     */
    public static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    /**
     * Creates a completed CompletableFuture with the given result.
     * This is a utility method for consistent success handling.
     *
     * @param result The result to wrap
     * @return Completed CompletableFuture
     */
    public static <T> CompletableFuture<T> completedFuture(T result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Logs the execution context for debugging purposes.
     * This helps with troubleshooting context-related issues.
     */
    public static void logExecutionContext() {
        if (logger.isTraceEnabled()) {
            Context currentContext = Vertx.currentContext();
            if (currentContext != null) {
                logger.trace("Executing on Vert.x context: {}", currentContext);
            } else {
                logger.trace("Executing outside Vert.x context");
            }
        }
    }
}
