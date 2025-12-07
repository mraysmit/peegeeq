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

import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.QueueFactoryProvider;

/**
 * Provider interface for accessing services associated with a database setup.
 * 
 * This interface allows the REST layer to access services (subscription, dead letter,
 * health, queue factory) without depending on implementation modules. The implementation
 * is provided by the database layer and injected into the REST layer.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public interface ServiceProvider {

    /**
     * Gets the SubscriptionService for a setup.
     *
     * @param setupId The setup ID
     * @return The SubscriptionService for this setup, or null if not found
     */
    SubscriptionService getSubscriptionServiceForSetup(String setupId);

    /**
     * Gets the DeadLetterService for a setup.
     *
     * @param setupId The setup ID
     * @return The DeadLetterService for this setup, or null if not found
     */
    DeadLetterService getDeadLetterServiceForSetup(String setupId);

    /**
     * Gets the HealthService for a setup.
     *
     * @param setupId The setup ID
     * @return The HealthService for this setup, or null if not found
     */
    HealthService getHealthServiceForSetup(String setupId);

    /**
     * Gets the QueueFactoryProvider for a setup.
     *
     * @param setupId The setup ID
     * @return The QueueFactoryProvider for this setup, or null if not found
     */
    QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId);
}

