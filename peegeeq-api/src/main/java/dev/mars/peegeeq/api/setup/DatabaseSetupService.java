package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.Future;
import java.util.Set;

/**
 * Database setup service interface using Vert.x 5.x reactive patterns.
 *
 * This interface provides methods for creating and managing database setups
 * using modern Vert.x 5.x composable Future patterns instead of blocking operations.
 */
public interface DatabaseSetupService {
    Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request);
    Future<Void> destroySetup(String setupId);
    Future<DatabaseSetupStatus> getSetupStatus(String setupId);
    Future<DatabaseSetupResult> getSetupResult(String setupId);
    Future<Void> addQueue(String setupId, QueueConfig queueConfig);
    Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig);

    /**
     * Gets all active setup IDs using Vert.x 5.x composable Future patterns.
     * @return A Future that completes with a set of active setup IDs
     */
    Future<Set<String>> getAllActiveSetupIds();
}