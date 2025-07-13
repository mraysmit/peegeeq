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


import java.time.Duration;
import java.util.Map;

/**
 * Configuration interface for queue implementations.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Configuration interface for queue implementations.
 * This interface provides a consistent way to configure
 * different queue implementations without exposing
 * implementation-specific configuration details.
 */
public interface QueueConfiguration {
    
    /**
     * Gets the database connection URL.
     * 
     * @return The database connection URL
     */
    String getDatabaseUrl();
    
    /**
     * Gets the database username.
     * 
     * @return The database username
     */
    String getUsername();
    
    /**
     * Gets the database password.
     * 
     * @return The database password
     */
    String getPassword();
    
    /**
     * Gets the maximum number of connections in the pool.
     * 
     * @return The maximum pool size
     */
    int getMaxPoolSize();
    
    /**
     * Gets the minimum number of connections in the pool.
     * 
     * @return The minimum pool size
     */
    int getMinPoolSize();
    
    /**
     * Gets the connection timeout duration.
     * 
     * @return The connection timeout
     */
    Duration getConnectionTimeout();
    
    /**
     * Gets the idle timeout duration for connections.
     * 
     * @return The idle timeout
     */
    Duration getIdleTimeout();
    
    /**
     * Gets the maximum lifetime for connections.
     * 
     * @return The maximum lifetime
     */
    Duration getMaxLifetime();
    
    /**
     * Gets the instance ID for metrics and identification.
     * 
     * @return The instance ID
     */
    String getInstanceId();
    
    /**
     * Checks if metrics collection is enabled.
     * 
     * @return true if metrics are enabled, false otherwise
     */
    boolean isMetricsEnabled();
    
    /**
     * Checks if health checks are enabled.
     * 
     * @return true if health checks are enabled, false otherwise
     */
    boolean isHealthChecksEnabled();
    
    /**
     * Gets the health check interval.
     * 
     * @return The health check interval
     */
    Duration getHealthCheckInterval();
    
    /**
     * Checks if schema migrations should be run automatically.
     * 
     * @return true if auto-migration is enabled, false otherwise
     */
    boolean isAutoMigrationEnabled();
    
    /**
     * Gets additional implementation-specific configuration properties.
     * 
     * @return A map of additional configuration properties
     */
    Map<String, Object> getAdditionalProperties();
    
    /**
     * Gets a specific configuration property.
     * 
     * @param key The property key
     * @return The property value, or null if not found
     */
    Object getProperty(String key);
    
    /**
     * Gets a specific configuration property with a default value.
     * 
     * @param key The property key
     * @param defaultValue The default value to return if the property is not found
     * @param <T> The type of the property value
     * @return The property value, or the default value if not found
     */
    <T> T getProperty(String key, T defaultValue);
}
