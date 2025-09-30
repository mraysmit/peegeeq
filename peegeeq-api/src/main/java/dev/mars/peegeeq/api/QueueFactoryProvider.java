package dev.mars.peegeeq.api;

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

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service provider interface for creating queue factories.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Service provider interface for creating queue factories.
 * This interface allows for pluggable queue implementations
 * and provides a unified way to create different types of queue factories.
 */
public interface QueueFactoryProvider {
    
    /**
     * Creates a queue factory of the specified type.
     * 
     * @param implementationType The type of implementation (e.g., "native", "outbox")
     * @param databaseService The database service to use
     * @param configuration Additional configuration parameters
     * @return A queue factory instance
     * @throws IllegalArgumentException if the implementation type is not supported
     */
    QueueFactory createFactory(String implementationType, 
                              DatabaseService databaseService, 
                              Map<String, Object> configuration);
    
    /**
     * Creates a queue factory of the specified type with default configuration.
     * 
     * @param implementationType The type of implementation (e.g., "native", "outbox")
     * @param databaseService The database service to use
     * @return A queue factory instance
     * @throws IllegalArgumentException if the implementation type is not supported
     */
    QueueFactory createFactory(String implementationType, DatabaseService databaseService);
    
    /**
     * Gets the set of supported implementation types.
     * 
     * @return A set of supported implementation type names
     */
    Set<String> getSupportedTypes();
    
    /**
     * Checks if the specified implementation type is supported.
     * 
     * @param implementationType The implementation type to check
     * @return true if supported, false otherwise
     */
    boolean isTypeSupported(String implementationType);
    
    /**
     * Gets the default implementation type.
     *
     * @return The name of the default implementation type
     * @throws IllegalStateException if no implementations are registered
     */
    String getDefaultType();

    /**
     * Gets the best available factory type, preferring native if available, falling back to outbox.
     * This method does not throw an exception if no implementations are available.
     *
     * @return Optional containing the best available factory type, or empty if no factories are registered
     */
    java.util.Optional<String> getBestAvailableType();

    /**
     * Gets configuration schema for the specified implementation type.
     * This can be used for validation or documentation purposes.
     *
     * @param implementationType The implementation type
     * @return A map describing the configuration schema
     */
    Map<String, Object> getConfigurationSchema(String implementationType);

    /**
     * Creates a queue factory using a named configuration template.
     *
     * @param implementationType The type of implementation (e.g., "native", "outbox")
     * @param configurationName The name of the predefined configuration (e.g., "high-throughput", "low-latency")
     * @param databaseService The database service to use
     * @param additionalConfig Additional configuration parameters to override the named configuration
     * @return A queue factory instance
     * @throws IllegalArgumentException if the implementation type is not supported
     */
    default QueueFactory createNamedFactory(String implementationType,
                                          String configurationName,
                                          DatabaseService databaseService,
                                          Map<String, Object> additionalConfig) {
        // Default implementation delegates to regular createFactory
        return createFactory(implementationType, databaseService, additionalConfig);
    }

    /**
     * Creates a queue factory using a named configuration template with default additional config.
     *
     * @param implementationType The type of implementation (e.g., "native", "outbox")
     * @param configurationName The name of the predefined configuration (e.g., "high-throughput", "low-latency")
     * @param databaseService The database service to use
     * @return A queue factory instance
     * @throws IllegalArgumentException if the implementation type is not supported
     */
    default QueueFactory createNamedFactory(String implementationType,
                                          String configurationName,
                                          DatabaseService databaseService) {
        return createNamedFactory(implementationType, configurationName, databaseService, new HashMap<>());
    }
}
