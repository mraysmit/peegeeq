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

package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching SubscriptionService instances per setup.
 *
 * Each database setup has its own connection manager and therefore needs its own
 * SubscriptionService instance. This factory creates them on-demand and caches them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public class SubscriptionManagerFactory {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerFactory.class);

    private final DatabaseSetupService setupService;
    private final Map<String, SubscriptionService> managers = new ConcurrentHashMap<>();

    public SubscriptionManagerFactory(DatabaseSetupService setupService) {
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
