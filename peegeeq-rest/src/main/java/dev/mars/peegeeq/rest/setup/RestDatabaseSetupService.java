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

package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * REST-specific database setup service that delegates to an injected DatabaseSetupService.
 *
 * This service acts as a facade that:
 * 1. Delegates all DatabaseSetupService operations to the injected implementation
 * 2. Provides access to services (subscription, dead letter, health) via the ServiceProvider interface
 * 3. Manages factory registration callbacks for queue implementations
 *
 * This design ensures the REST layer only depends on peegeeq-api interfaces, not on
 * implementation modules like peegeeq-db.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 2.0
 * @deprecated Use {@link dev.mars.peegeeq.runtime.RuntimeDatabaseSetupService} from peegeeq-runtime instead.
 *             This class is retained for backward compatibility but will be removed in a future version.
 *             Migration: Replace usage with {@code PeeGeeQRuntime.createDatabaseSetupService()} or
 *             {@code PeeGeeQRuntime.bootstrap().getDatabaseSetupService()}.
 */
@Deprecated(since = "1.0", forRemoval = true)
public class RestDatabaseSetupService implements DatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(RestDatabaseSetupService.class);

    // The delegate that provides the actual implementation
    private final DatabaseSetupService delegate;

    // List of factory registration callbacks - invoked when a setup is created
    private final List<Consumer<QueueFactoryRegistrar>> factoryRegistrations = new ArrayList<>();

    /**
     * Creates REST database setup service with an injected delegate.
     *
     * @param delegate The DatabaseSetupService implementation to delegate to
     */
    public RestDatabaseSetupService(DatabaseSetupService delegate) {
        this.delegate = delegate;
        logger.info("RestDatabaseSetupService initialized with delegate: {}", delegate.getClass().getSimpleName());
    }

    /**
     * Adds a factory registration callback that will be invoked during setup.
     * This allows implementation modules to register their factories without
     * creating direct dependencies from the REST layer.
     *
     * The registration is passed to the delegate so it can apply the registration
     * when creating setups.
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    @Override
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        factoryRegistrations.add(registration);
        // Also pass to delegate so it can apply during setup
        delegate.addFactoryRegistration(registration);
        logger.debug("Added factory registration, passed to delegate");
    }

    /**
     * Gets the factory registrations for use by the delegate during setup.
     *
     * @return List of factory registration callbacks
     */
    public List<Consumer<QueueFactoryRegistrar>> getFactoryRegistrations() {
        return factoryRegistrations;
    }

    // ========== DatabaseSetupService delegation ==========

    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        return delegate.createCompleteSetup(request);
    }

    @Override
    public CompletableFuture<Void> destroySetup(String setupId) {
        return delegate.destroySetup(setupId);
    }

    @Override
    public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) {
        return delegate.getSetupStatus(setupId);
    }

    @Override
    public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
        return delegate.getSetupResult(setupId);
    }

    @Override
    public CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) {
        return delegate.addQueue(setupId, queueConfig);
    }

    @Override
    public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        return delegate.addEventStore(setupId, eventStoreConfig);
    }

    @Override
    public CompletableFuture<Set<String>> getAllActiveSetupIds() {
        return delegate.getAllActiveSetupIds();
    }

    // ========== ServiceProvider delegation ==========

    @Override
    public SubscriptionService getSubscriptionServiceForSetup(String setupId) {
        return delegate.getSubscriptionServiceForSetup(setupId);
    }

    @Override
    public DeadLetterService getDeadLetterServiceForSetup(String setupId) {
        return delegate.getDeadLetterServiceForSetup(setupId);
    }

    @Override
    public HealthService getHealthServiceForSetup(String setupId) {
        return delegate.getHealthServiceForSetup(setupId);
    }

    @Override
    public QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) {
        return delegate.getQueueFactoryProviderForSetup(setupId);
    }
}
