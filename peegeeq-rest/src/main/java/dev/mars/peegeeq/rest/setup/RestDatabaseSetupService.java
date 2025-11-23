package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Function;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST-specific database setup service that handles queue factory and event store factory registration.
 * This service properly registers implementations using dependency injection
 * rather than reflection, following proper architectural patterns.
 */
public class RestDatabaseSetupService extends PeeGeeQDatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(RestDatabaseSetupService.class);
    
    // Cache of managers accessible from REST layer
    private final Map<String, PeeGeeQManager> managerCache = new ConcurrentHashMap<>();

    /**
     * Creates REST database setup service without EventStore support.
     */
    public RestDatabaseSetupService() {
        super((Function<PeeGeeQManager, EventStoreFactory>) null);
    }
    
    /**
     * Creates REST database setup service with EventStore support.
     * 
     * @param eventStoreFactoryProvider Function that creates an EventStoreFactory given a PeeGeeQManager
     */
    public RestDatabaseSetupService(Function<PeeGeeQManager, EventStoreFactory> eventStoreFactoryProvider) {
        super(eventStoreFactoryProvider);
    }

    @Override
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        // Cache the manager for later access
        String setupId = extractSetupId(manager);
        logger.info("Attempting to cache PeeGeeQManager for setup: {} (extracted from profile)", setupId);
        if (setupId != null) {
            managerCache.put(setupId, manager);
            logger.info("✓ Cached PeeGeeQManager for setup: {} - Cache now has {} entries", setupId, managerCache.size());
        } else {
            logger.warn("✗ Could not cache manager - setupId was null!");
        }
        
        try {
            var queueFactoryProvider = manager.getQueueFactoryProvider();

            if (queueFactoryProvider instanceof QueueFactoryRegistrar) {
                QueueFactoryRegistrar registrar = (QueueFactoryRegistrar) queueFactoryProvider;

                // Register native queue factory (available as direct dependency)
                PgNativeFactoryRegistrar.registerWith(registrar);
                logger.info("Registered native queue factory implementation");

                // Register outbox queue factory (available as direct dependency)
                OutboxFactoryRegistrar.registerWith(registrar);
                logger.info("Registered outbox queue factory implementation");

                logger.info("Successfully registered all queue factory implementations. Available types: {}",
                    queueFactoryProvider.getSupportedTypes());
            } else {
                logger.warn("Queue factory provider does not support registration");
            }
        } catch (Exception e) {
            logger.error("Failed to register queue factory implementations: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Gets the PeeGeeQManager for a setup.
     * This allows REST handlers to access the manager for creating service instances.
     * 
     * @param setupId The setup ID
     * @return The manager for this setup, or null if not found
     */
    public PeeGeeQManager getManagerForSetup(String setupId) {
        logger.info("Looking up manager for setupId: {} - Cache has {} entries: {}", 
            setupId, managerCache.size(), managerCache.keySet());
        PeeGeeQManager manager = managerCache.get(setupId);
        if (manager == null) {
            logger.warn("✗ Manager not found for setupId: {}", setupId);
        } else {
            logger.info("✓ Found manager for setupId: {}", setupId);
        }
        return manager;
    }
    
    /**
     * Extracts setup ID from manager's configuration.
     */
    private String extractSetupId(PeeGeeQManager manager) {
        try {
            // The configuration profile typically contains the setup ID
            return manager.getConfiguration().getProfile();
        } catch (Exception e) {
            logger.warn("Could not extract setup ID from manager", e);
            return null;
        }
    }
}
