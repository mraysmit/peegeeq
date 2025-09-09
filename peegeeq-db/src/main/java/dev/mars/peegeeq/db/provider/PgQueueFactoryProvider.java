package dev.mars.peegeeq.db.provider;

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


import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL implementation of QueueFactoryProvider.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of QueueFactoryProvider.
 * This class provides a registry for different PostgreSQL-based queue implementations
 * and allows for pluggable queue factory creation.
 */
public class PgQueueFactoryProvider implements QueueFactoryProvider, QueueFactoryRegistrar {
    
    private static final Logger logger = LoggerFactory.getLogger(PgQueueFactoryProvider.class);

    private static final String DEFAULT_TYPE = "native";

    // Registry of factory creators
    private final Map<String, QueueFactoryRegistrar.QueueFactoryCreator> factoryCreators = new HashMap<>();

    // Configuration for runtime behavior
    private final PeeGeeQConfiguration peeGeeQConfiguration;

    public PgQueueFactoryProvider() {
        this(null);
    }

    public PgQueueFactoryProvider(PeeGeeQConfiguration configuration) {
        this.peeGeeQConfiguration = configuration;
        // Register built-in factory types
        registerBuiltInFactories();

        // Critical error check - system cannot function without factory types
        logger.debug("FACTORY-PROVIDER-DEBUG: Factory creators size = {}", factoryCreators.size());
        if (factoryCreators.isEmpty()) {
            logger.debug("FACTORY-PROVIDER-DEBUG: ERROR CONDITION - 0 factory types!");
            logger.error("CRITICAL: Initialized PgQueueFactoryProvider with 0 factory types! " +
                "The queue system will not function. Please ensure that queue implementation modules " +
                "(peegeeq-native, peegeeq-outbox) are on the classpath and properly registered.");
        } else {
            logger.debug("FACTORY-PROVIDER-DEBUG: SUCCESS - {} factory types", factoryCreators.size());
            logger.info("Initialized PgQueueFactoryProvider with {} factory types: {}",
                factoryCreators.size(), factoryCreators.keySet());
        }
    }
    
    @Override
    public QueueFactory createFactory(String implementationType,
                                    DatabaseService databaseService,
                                    Map<String, Object> configuration) {
        if (implementationType == null || implementationType.trim().isEmpty()) {
            throw new IllegalArgumentException("Implementation type cannot be null or empty");
        }

        if (databaseService == null) {
            throw new IllegalArgumentException("Database service cannot be null");
        }

        // Critical check - ensure factories are registered
        if (factoryCreators.isEmpty()) {
            throw new IllegalStateException("CRITICAL: No queue factory implementations are registered! " +
                "The queue system cannot function. Please ensure that queue implementation modules " +
                "(peegeeq-native, peegeeq-outbox) are on the classpath and properly registered using " +
                "PgNativeFactoryRegistrar.registerWith() or OutboxFactoryRegistrar.registerWith().");
        }

        QueueFactoryRegistrar.QueueFactoryCreator creator = factoryCreators.get(implementationType.toLowerCase());
        if (creator == null) {
            throw new IllegalArgumentException("Unsupported implementation type: " + implementationType +
                ". Available types: " + factoryCreators.keySet() +
                ". Please ensure the requested implementation module is registered.");
        }
        
        try {
            logger.info("Creating queue factory of type: {}", implementationType);

            // Prepare configuration with PeeGeeQConfiguration if available
            Map<String, Object> effectiveConfiguration = configuration != null ? new HashMap<>(configuration) : new HashMap<>();
            if (peeGeeQConfiguration != null) {
                effectiveConfiguration.put("peeGeeQConfiguration", peeGeeQConfiguration);
            }

            QueueFactory factory = creator.create(databaseService, effectiveConfiguration);
            logger.info("Successfully created queue factory of type: {}", implementationType);
            return factory;
        } catch (Exception e) {
            logger.error("Failed to create queue factory of type: {}", implementationType, e);
            throw new RuntimeException("Failed to create queue factory: " + e.getMessage(), e);
        }
    }
    
    @Override
    public QueueFactory createFactory(String implementationType, DatabaseService databaseService) {
        return createFactory(implementationType, databaseService, new HashMap<>());
    }
    
    @Override
    public Set<String> getSupportedTypes() {
        return Set.copyOf(factoryCreators.keySet());
    }
    
    @Override
    public boolean isTypeSupported(String implementationType) {
        return implementationType != null && factoryCreators.containsKey(implementationType.toLowerCase());
    }
    
    @Override
    public String getDefaultType() {
        // Return the best available type instead of a hardcoded default
        return getBestAvailableType();
    }

    /**
     * Gets the best available factory type, preferring native if available, falling back to outbox.
     */
    public String getBestAvailableType() {
        if (isTypeSupported("native")) {
            return "native";
        } else if (isTypeSupported("outbox")) {
            return "outbox";
        } else if (!factoryCreators.isEmpty()) {
            // Return the first available factory type
            return factoryCreators.keySet().iterator().next();
        } else {
            logger.error("CRITICAL: No queue factory implementations are registered! Available types: {}", factoryCreators.keySet());
            throw new IllegalStateException("CRITICAL: No queue factory implementations are registered. " +
                "The queue system cannot function. Please ensure that at least one queue implementation module " +
                "(peegeeq-native, peegeeq-outbox) is on the classpath and properly registered using " +
                "PgNativeFactoryRegistrar.registerWith() or OutboxFactoryRegistrar.registerWith().");
        }
    }
    
    @Override
    public Map<String, Object> getConfigurationSchema(String implementationType) {
        if (!isTypeSupported(implementationType)) {
            throw new IllegalArgumentException("Unsupported implementation type: " + implementationType);
        }

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("description", "Configuration for " + implementationType + " queue implementation");

        Map<String, Object> properties = new HashMap<>();

        // Common configuration options
        properties.put("batch-size", Map.of("type", "integer", "default", 10,
            "description", "Number of messages to process in a batch"));
        properties.put("polling-interval", Map.of("type", "string", "default", "PT1S",
            "description", "Interval between polling for new messages"));
        properties.put("max-retries", Map.of("type", "integer", "default", 3,
            "description", "Maximum number of retry attempts"));
        properties.put("visibility-timeout", Map.of("type", "string", "default", "PT30S",
            "description", "Time a message remains invisible after being consumed"));
        properties.put("dead-letter-enabled", Map.of("type", "boolean", "default", true,
            "description", "Whether to enable dead letter queue"));

        // Performance tuning options
        properties.put("prefetch-count", Map.of("type", "integer", "default", 10,
            "description", "Number of messages to prefetch"));
        properties.put("concurrent-consumers", Map.of("type", "integer", "default", 1,
            "description", "Number of concurrent consumers"));
        properties.put("buffer-size", Map.of("type", "integer", "default", 100,
            "description", "Internal buffer size for message processing"));

        // Implementation-specific options
        if ("native".equals(implementationType.toLowerCase())) {
            properties.put("listen-notify-enabled", Map.of("type", "boolean", "default", true,
                "description", "Enable PostgreSQL LISTEN/NOTIFY"));
            properties.put("connection-pool-size", Map.of("type", "integer", "default", 5,
                "description", "Size of connection pool for LISTEN/NOTIFY"));
        } else if ("outbox".equals(implementationType.toLowerCase())) {
            properties.put("retention-period", Map.of("type", "string", "default", "P7D",
                "description", "How long to retain processed messages"));
            properties.put("cleanup-interval", Map.of("type", "string", "default", "PT1H",
                "description", "Interval for cleanup operations"));
        }

        schema.put("properties", properties);
        return schema;
    }
    
    /**
     * Creates a factory with named configuration support.
     *
     * @param implementationType The queue implementation type
     * @param configurationName The name of the configuration to use
     * @param databaseService The database service
     * @param additionalConfig Additional configuration overrides
     * @return A queue factory instance
     */
    public QueueFactory createNamedFactory(String implementationType,
                                         String configurationName,
                                         DatabaseService databaseService,
                                         Map<String, Object> additionalConfig) {

        // Load named configuration
        Map<String, Object> namedConfig = loadNamedConfiguration(configurationName);

        // Merge configurations (additional config takes precedence)
        Map<String, Object> mergedConfig = new HashMap<>(namedConfig);
        if (additionalConfig != null) {
            mergedConfig.putAll(additionalConfig);
        }

        return createFactory(implementationType, databaseService, mergedConfig);
    }

    /**
     * Loads a named configuration from predefined templates.
     *
     * @param configurationName The name of the configuration
     * @return A map of configuration properties
     */
    private Map<String, Object> loadNamedConfiguration(String configurationName) {
        Map<String, Object> config = new HashMap<>();

        // Predefined configurations for different use cases
        switch (configurationName.toLowerCase()) {
            case "high-throughput":
                config.put("batch-size", 100);
                config.put("polling-interval", "PT0.1S");
                config.put("prefetch-count", 50);
                config.put("concurrent-consumers", 10);
                config.put("buffer-size", 1000);
                break;
            case "low-latency":
                config.put("batch-size", 1);
                config.put("polling-interval", "PT0.01S");
                config.put("prefetch-count", 1);
                config.put("concurrent-consumers", 1);
                config.put("buffer-size", 10);
                break;
            case "reliable":
                config.put("max-retries", 10);
                config.put("dead-letter-enabled", true);
                config.put("retention-period", "P30D");
                config.put("visibility-timeout", "PT300S");
                break;
            case "durable":
                config.put("retention-period", "P30D");
                config.put("max-retries", 5);
                config.put("dead-letter-enabled", true);
                config.put("cleanup-interval", "PT6H");
                break;
            default:
                logger.warn("Unknown named configuration: {}, using defaults", configurationName);
        }

        return config;
    }

    /**
     * Registers a custom factory creator.
     *
     * @param implementationType The implementation type name
     * @param creator The factory creator
     */
    @Override
    public void registerFactory(String implementationType, QueueFactoryRegistrar.QueueFactoryCreator creator) {
        if (implementationType == null || implementationType.trim().isEmpty()) {
            throw new IllegalArgumentException("Implementation type cannot be null or empty");
        }

        if (creator == null) {
            throw new IllegalArgumentException("Factory creator cannot be null");
        }

        factoryCreators.put(implementationType.toLowerCase(), creator);
        logger.info("Registered factory creator for type: {}", implementationType);
    }
    
    /**
     * Unregisters a factory creator.
     *
     * @param implementationType The implementation type name
     */
    @Override
    public void unregisterFactory(String implementationType) {
        if (implementationType != null) {
            factoryCreators.remove(implementationType.toLowerCase());
            logger.info("Unregistered factory creator for type: {}", implementationType);
        }
    }
    
    private void registerBuiltInFactories() {
        // No built-in factories registered by default
        // Factories should be registered by their respective modules
        // through dependency injection or explicit registration
        logger.info("PgQueueFactoryProvider initialized - factories should be registered by their respective modules");
    }


}
