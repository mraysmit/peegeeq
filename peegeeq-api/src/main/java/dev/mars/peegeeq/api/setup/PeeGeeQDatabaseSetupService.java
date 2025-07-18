package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PeeGeeQDatabaseSetupService implements DatabaseSetupService {
    private final Map<String, DatabaseSetupResult> activeSetups = new ConcurrentHashMap<>();
    
    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            // Create PeeGeeQ configuration
            PeeGeeQConfiguration config = createConfiguration(request.getDatabaseConfig());
            
            // Initialize manager
            PeeGeeQManager manager = new PeeGeeQManager(config);
            manager.start();
            
            // Create queue factories and event stores
            Map<String, QueueFactory> queueFactories = createQueueFactories(manager, request.getQueues());
            Map<String, EventStore<?>> eventStores = createEventStores(manager, request.getEventStores());
            
            DatabaseSetupResult result = new DatabaseSetupResult(
                request.getSetupId(), queueFactories, eventStores, DatabaseSetupStatus.ACTIVE
            );
            
            activeSetups.put(request.getSetupId(), result);
            return result;
        });
    }
}