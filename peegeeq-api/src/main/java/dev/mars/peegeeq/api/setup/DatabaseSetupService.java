package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import java.util.concurrent.CompletableFuture;
import java.util.Set;

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
}