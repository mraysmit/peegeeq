package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.Future;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Database setup service interface with dual API support.
 *
 * This interface provides methods for creating and managing database setups.
 * External API uses CompletableFuture for non-Vert.x consumers, with reactive
 * convenience methods for Vert.x consumers.
 */
public interface DatabaseSetupService {
    CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request);
    CompletableFuture<Void> destroySetup(String setupId);
    CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId);
    CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId);
    CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig);
    CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig);

    /**
     * Gets all active setup IDs.
     * @return A CompletableFuture that completes with a set of active setup IDs
     */
    CompletableFuture<Set<String>> getAllActiveSetupIds();

    // Reactive convenience methods for Vert.x consumers
    default Future<DatabaseSetupResult> createCompleteSetupReactive(DatabaseSetupRequest request) {
        return Future.fromCompletionStage(createCompleteSetup(request));
    }

    default Future<Void> destroySetupReactive(String setupId) {
        return Future.fromCompletionStage(destroySetup(setupId));
    }

    default Future<DatabaseSetupStatus> getSetupStatusReactive(String setupId) {
        return Future.fromCompletionStage(getSetupStatus(setupId));
    }

    default Future<DatabaseSetupResult> getSetupResultReactive(String setupId) {
        return Future.fromCompletionStage(getSetupResult(setupId));
    }

    default Future<Void> addQueueReactive(String setupId, QueueConfig queueConfig) {
        return Future.fromCompletionStage(addQueue(setupId, queueConfig));
    }

    default Future<Void> addEventStoreReactive(String setupId, EventStoreConfig eventStoreConfig) {
        return Future.fromCompletionStage(addEventStore(setupId, eventStoreConfig));
    }

    default Future<Set<String>> getAllActiveSetupIdsReactive() {
        return Future.fromCompletionStage(getAllActiveSetupIds());
    }
}