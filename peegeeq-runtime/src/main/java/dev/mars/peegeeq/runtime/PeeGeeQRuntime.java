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
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Main factory class for PeeGeeQ runtime composition.
 *
 * This class is the single entry point for creating PeeGeeQ services.
 * It wires together all implementation modules (peegeeq-db, peegeeq-native,
 * peegeeq-outbox, peegeeq-bitemporal) and exposes factory methods that
 * return API interfaces.
 * 
 * Usage:
 * <pre>{@code
 * // Create a database setup service with all features enabled
 * DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
 * 
 * // Or with custom configuration
 * RuntimeConfig config = RuntimeConfig.builder()
 *     .enableNativeQueues(true)
 *     .enableOutboxQueues(false)
 *     .build();
 * DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService(config);
 * 
 * // Or bootstrap a complete context
 * PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-06
 * @version 1.0
 */
public final class PeeGeeQRuntime {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRuntime.class);
    
    // Utility class - prevent instantiation
    private PeeGeeQRuntime() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    /**
     * Creates a DatabaseSetupService with all features enabled.
     * 
     * This is the simplest way to get a fully configured setup service
     * with native queues, outbox queues, and bi-temporal event store support.
     * 
     * @return a fully configured DatabaseSetupService
     */
    public static DatabaseSetupService createDatabaseSetupService() {
        return createDatabaseSetupService(RuntimeConfig.defaults());
    }
    
    /**
     * Creates a DatabaseSetupService with the specified configuration.
     * 
     * @param config the runtime configuration specifying which features to enable
     * @return a configured DatabaseSetupService
     */
    public static DatabaseSetupService createDatabaseSetupService(RuntimeConfig config) {
        Objects.requireNonNull(config, "RuntimeConfig cannot be null");
        
        logger.info("Creating DatabaseSetupService with config: {}", config);
        
        // Create the delegate with optional bi-temporal event store support
        PeeGeeQDatabaseSetupService delegate;
        if (config.isBiTemporalEventStoreEnabled()) {
            delegate = new PeeGeeQDatabaseSetupService(
                    manager -> new BiTemporalEventStoreFactory(manager)
            );
            logger.debug("Bi-temporal event store factory enabled");
        } else {
            delegate = new PeeGeeQDatabaseSetupService();
            logger.debug("Bi-temporal event store factory disabled");
        }
        
        // Wrap with runtime facade
        RuntimeDatabaseSetupService setupService = new RuntimeDatabaseSetupService(delegate);
        
        // Register queue factories based on configuration
        if (config.isNativeQueuesEnabled()) {
            setupService.addFactoryRegistration(PgNativeFactoryRegistrar::registerWith);
            logger.debug("Native queue factory registered");
        }
        
        if (config.isOutboxQueuesEnabled()) {
            setupService.addFactoryRegistration(OutboxFactoryRegistrar::registerWith);
            logger.debug("Outbox queue factory registered");
        }
        
        logger.info("DatabaseSetupService created successfully");
        return setupService;
    }
    
    /**
     * Bootstraps a complete PeeGeeQ context with all services.
     * 
     * @param config the runtime configuration
     * @return a PeeGeeQContext containing all configured services
     */
    public static PeeGeeQContext bootstrap(RuntimeConfig config) {
        Objects.requireNonNull(config, "RuntimeConfig cannot be null");
        
        logger.info("Bootstrapping PeeGeeQ context with config: {}", config);
        
        DatabaseSetupService setupService = createDatabaseSetupService(config);
        
        PeeGeeQContext context = new PeeGeeQContext(setupService, config);
        
        logger.info("PeeGeeQ context bootstrapped successfully: {}", context);
        return context;
    }
    
    /**
     * Bootstraps a complete PeeGeeQ context with default configuration.
     * 
     * @return a PeeGeeQContext with all features enabled
     */
    public static PeeGeeQContext bootstrap() {
        return bootstrap(RuntimeConfig.defaults());
    }
}

