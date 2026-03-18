/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.Future;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Database setup service interface with dual API support.
 *
 * This interface provides methods for creating and managing database setups.
 * External API uses CompletableFuture for non-Vert.x consumers, with reactive
 * convenience methods for Vert.x consumers.
 *
 * Extends ServiceProvider to provide access to services (subscription, dead letter,
 * health) for each setup without requiring direct dependencies on implementation modules.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public interface DatabaseSetupService extends ServiceProvider {
    Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request);
    Future<Void> destroySetup(String setupId);
    Future<DatabaseSetupStatus> getSetupStatus(String setupId);
    Future<DatabaseSetupResult> getSetupResult(String setupId);
    Future<Void> addQueue(String setupId, QueueConfig queueConfig);
    Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig);

    /**
     * Gets all active setup IDs.
     * @return A Future that completes with a set of active setup IDs
     */
    Future<Set<String>> getAllActiveSetupIds();

    // ========================================
    // CompletableFuture Bridge Methods
    // ========================================

    default CompletableFuture<DatabaseSetupResult> createCompleteSetupAsync(DatabaseSetupRequest request) {
        return createCompleteSetup(request).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<Void> destroySetupAsync(String setupId) {
        return destroySetup(setupId).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<DatabaseSetupStatus> getSetupStatusAsync(String setupId) {
        return getSetupStatus(setupId).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<DatabaseSetupResult> getSetupResultAsync(String setupId) {
        return getSetupResult(setupId).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<Void> addQueueAsync(String setupId, QueueConfig queueConfig) {
        return addQueue(setupId, queueConfig).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<Void> addEventStoreAsync(String setupId, EventStoreConfig eventStoreConfig) {
        return addEventStore(setupId, eventStoreConfig).toCompletionStage().toCompletableFuture();
    }

    default CompletableFuture<Set<String>> getAllActiveSetupIdsAsync() {
        return getAllActiveSetupIds().toCompletionStage().toCompletableFuture();
    }

    /**
     * Adds a factory registration callback that will be invoked during setup.
     * This allows implementation modules to register their queue factories without
     * creating direct dependencies.
     *
     * Default implementation does nothing - implementations should override this
     * if they support factory registration.
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    default void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        // Default implementation does nothing
    }
}