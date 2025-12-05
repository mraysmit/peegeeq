package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.rest.setup.RestDatabaseSetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching SubscriptionService instances per setup.
 *
 * Each database setup has its own connection manager and therefore needs its own
 * SubscriptionService instance. This factory creates them on-demand and caches them.
 */
public class SubscriptionManagerFactory {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerFactory.class);

    private final RestDatabaseSetupService setupService;
    private final Map<String, SubscriptionService> managers = new ConcurrentHashMap<>();

    public SubscriptionManagerFactory(RestDatabaseSetupService setupService) {
        this.setupService = setupService;
    }

    /**
     * Gets or creates a SubscriptionService for the given setup.
     *
     * @param setupId The setup ID
     * @return SubscriptionService instance for this setup
     * @throws IllegalStateException if setup not found or not ready
     */
    public SubscriptionService getManager(String setupId) {
        logger.info("SubscriptionManagerFactory.getManager called with setupId: {}", setupId);
        return managers.computeIfAbsent(setupId, id -> {
            logger.info("Creating new SubscriptionService for setup: {}", id);
            // Get the subscription service from the setup service
            SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
            if (subscriptionService == null) {
                logger.error("Setup not found in cache! setupId={}", setupId);
                throw new IllegalStateException("Setup not found or not ready: " + setupId);
            }

            logger.info("Created SubscriptionService for setup: {}", setupId);
            return subscriptionService;
        });
    }

    /**
     * Removes the cached SubscriptionService for a setup (e.g., when setup is destroyed).
     */
    public void removeManager(String setupId) {
        SubscriptionService removed = managers.remove(setupId);
        if (removed != null) {
            logger.info("Removed SubscriptionService for setup: {}", setupId);
        }
    }
}
