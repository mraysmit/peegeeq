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

package dev.mars.peegeeq.runtime;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;

import java.util.Objects;

/**
 * Container for all PeeGeeQ services instantiated during runtime bootstrap.
 * 
 * This class provides a single point of access to all PeeGeeQ services,
 * making it easy to pass the complete runtime context to consumers like
 * the REST server.
 * 
 * The context is immutable once created and holds references to all
 * configured services based on the RuntimeConfig used during bootstrap.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-06
 * @version 1.0
 */
public final class PeeGeeQContext {
    
    private final DatabaseSetupService databaseSetupService;
    private final RuntimeConfig config;
    
    /**
     * Creates a new PeeGeeQContext with the specified services.
     * 
     * @param databaseSetupService the database setup service
     * @param config the runtime configuration used to create this context
     */
    PeeGeeQContext(DatabaseSetupService databaseSetupService, RuntimeConfig config) {
        this.databaseSetupService = Objects.requireNonNull(databaseSetupService, 
                "DatabaseSetupService cannot be null");
        this.config = Objects.requireNonNull(config, "RuntimeConfig cannot be null");
    }
    
    /**
     * Returns the database setup service.
     * 
     * This service is the main entry point for creating and managing
     * database setups, queues, and event stores.
     * 
     * @return the database setup service
     */
    public DatabaseSetupService getDatabaseSetupService() {
        return databaseSetupService;
    }
    
    /**
     * Returns the runtime configuration used to create this context.
     * 
     * @return the runtime configuration
     */
    public RuntimeConfig getConfig() {
        return config;
    }
    
    /**
     * Returns whether native queues are available in this context.
     * 
     * @return true if native queues are enabled
     */
    public boolean hasNativeQueues() {
        return config.isNativeQueuesEnabled();
    }
    
    /**
     * Returns whether outbox queues are available in this context.
     * 
     * @return true if outbox queues are enabled
     */
    public boolean hasOutboxQueues() {
        return config.isOutboxQueuesEnabled();
    }
    
    /**
     * Returns whether bi-temporal event store is available in this context.
     * 
     * @return true if bi-temporal event store is enabled
     */
    public boolean hasBiTemporalEventStore() {
        return config.isBiTemporalEventStoreEnabled();
    }
    
    @Override
    public String toString() {
        return "PeeGeeQContext{" +
                "databaseSetupService=" + databaseSetupService.getClass().getSimpleName() +
                ", config=" + config +
                '}';
    }
}

