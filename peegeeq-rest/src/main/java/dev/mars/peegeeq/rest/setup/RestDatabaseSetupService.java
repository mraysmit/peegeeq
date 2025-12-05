package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST-specific database setup service that handles queue factory and event store factory registration.
 * This service properly registers implementations using dependency injection
 * rather than reflection, following proper architectural patterns.
 *
 * <p>Factory registrations are provided externally via {@link #addFactoryRegistration(Consumer)},
 * allowing the REST layer to remain decoupled from implementation modules.</p>
 */
public class RestDatabaseSetupService extends PeeGeeQDatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(RestDatabaseSetupService.class);

    // Cache of managers accessible from REST layer
    private final Map<String, PeeGeeQManager> managerCache = new ConcurrentHashMap<>();

    // List of factory registration callbacks - injected externally
    private final List<Consumer<QueueFactoryRegistrar>> factoryRegistrations = new ArrayList<>();

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

    /**
     * Adds a factory registration callback that will be invoked during setup.
     * This allows implementation modules to register their factories without
     * creating direct dependencies from the REST layer.
     *
     * <p>Example usage from application startup:</p>
     * <pre>{@code
     * RestDatabaseSetupService setupService = new RestDatabaseSetupService();
     * setupService.addFactoryRegistration(PgNativeFactoryRegistrar::registerWith);
     * setupService.addFactoryRegistration(OutboxFactoryRegistrar::registerWith);
     * }</pre>
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        factoryRegistrations.add(registration);
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

                // Invoke all registered factory registration callbacks
                for (Consumer<QueueFactoryRegistrar> registration : factoryRegistrations) {
                    try {
                        registration.accept(registrar);
                        logger.info("Registered queue factory implementation via callback");
                    } catch (Exception e) {
                        logger.error("Failed to register queue factory via callback: {}", e.getMessage(), e);
                    }
                }

                logger.info("Successfully registered {} queue factory implementations. Available types: {}",
                    factoryRegistrations.size(), queueFactoryProvider.getSupportedTypes());
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
     * Gets a SubscriptionService for a setup.
     * This returns the API interface type to maintain proper layering.
     *
     * @param setupId The setup ID
     * @return The SubscriptionService for this setup, or null if not found
     */
    public SubscriptionService getSubscriptionServiceForSetup(String setupId) {
        PeeGeeQManager manager = getManagerForSetup(setupId);
        if (manager == null) {
            return null;
        }
        return manager.createSubscriptionService();
    }

    /**
     * Gets a DeadLetterService for a setup.
     * This returns the API interface type to maintain proper layering.
     *
     * @param setupId The setup ID
     * @return The DeadLetterService for this setup, or null if not found
     */
    public DeadLetterService getDeadLetterServiceForSetup(String setupId) {
        PeeGeeQManager manager = getManagerForSetup(setupId);
        if (manager == null) {
            return null;
        }
        return manager.getDeadLetterQueueManager();
    }

    /**
     * Gets a HealthService for a setup.
     * This returns the API interface type to maintain proper layering.
     *
     * @param setupId The setup ID
     * @return The HealthService for this setup, or null if not found
     */
    public HealthService getHealthService(String setupId) {
        PeeGeeQManager manager = getManagerForSetup(setupId);
        if (manager == null) {
            return null;
        }
        return manager.getHealthCheckManager();
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
