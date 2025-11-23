package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.rest.setup.RestDatabaseSetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching SubscriptionManager instances per setup.
 * 
 * Each database setup has its own connection manager and therefore needs its own
 * SubscriptionManager instance. This factory creates them on-demand and caches them.
 */
public class SubscriptionManagerFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerFactory.class);
    
    private final RestDatabaseSetupService setupService;
    private final Map<String, SubscriptionManager> managers = new ConcurrentHashMap<>();
    
    public SubscriptionManagerFactory(RestDatabaseSetupService setupService) {
        this.setupService = setupService;
    }
    
    /**
     * Gets or creates a SubscriptionManager for the given setup.
     * 
     * @param setupId The setup ID
     * @return SubscriptionManager instance for this setup
     * @throws IllegalStateException if setup not found or not ready
     */
    public SubscriptionManager getManager(String setupId) {
        logger.info("SubscriptionManagerFactory.getManager called with setupId: {}", setupId);
        return managers.computeIfAbsent(setupId, id -> {
            logger.info("Creating new SubscriptionManager for setup: {}", id);
            // Get the manager from the setup service
            PeeGeeQManager manager = setupService.getManagerForSetup(setupId);
            if (manager == null) {
                logger.error("Setup not found in cache! setupId={}, Available setups in cache: {}", 
                    setupId, setupService.getClass().getSimpleName() + " cache contents");
                throw new IllegalStateException("Setup not found or not ready: " + setupId);
            }
            
            // Create SubscriptionManager using the manager's connection factory
            var connectionManager = manager.getClientFactory().getConnectionManager();
            // Use "peegeeq-main" as the service ID since that's the reactive pool created by PeeGeeQManager
            // The setupId is just used for caching the SubscriptionManager instance
            SubscriptionManager subManager = new SubscriptionManager(connectionManager, "peegeeq-main");
            
            logger.info("Created SubscriptionManager for setup: {} (using 'peegeeq-main' reactive pool)", setupId);
            return subManager;
        });
    }
    
    /**
     * Removes the cached SubscriptionManager for a setup (e.g., when setup is destroyed).
     */
    public void removeManager(String setupId) {
        SubscriptionManager removed = managers.remove(setupId);
        if (removed != null) {
            logger.info("Removed SubscriptionManager for setup: {}", setupId);
        }
    }
}
