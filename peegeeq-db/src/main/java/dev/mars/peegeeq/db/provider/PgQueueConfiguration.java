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


import dev.mars.peegeeq.api.QueueConfiguration;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL implementation of QueueConfiguration.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of QueueConfiguration.
 * This class wraps the existing PeeGeeQConfiguration to provide
 * a clean interface for configuration access.
 */
public class PgQueueConfiguration implements QueueConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(PgQueueConfiguration.class);
    
    private final PeeGeeQConfiguration configuration;
    private final Map<String, Object> additionalProperties;
    
    public PgQueueConfiguration(PeeGeeQConfiguration configuration) {
        this.configuration = configuration;
        this.additionalProperties = new HashMap<>();
        logger.debug("Initialized PgQueueConfiguration");
    }
    
    public PgQueueConfiguration(PeeGeeQConfiguration configuration, Map<String, Object> additionalProperties) {
        this.configuration = configuration;
        this.additionalProperties = new HashMap<>(additionalProperties);
        logger.debug("Initialized PgQueueConfiguration with additional properties");
    }
    
    @Override
    public String getDatabaseUrl() {
        return configuration.getDatabaseConfig().getJdbcUrl();
    }

    @Override
    public String getUsername() {
        return configuration.getDatabaseConfig().getUsername();
    }

    @Override
    public String getPassword() {
        return configuration.getDatabaseConfig().getPassword();
    }

    @Override
    public int getMaxPoolSize() {
        return configuration.getPoolConfig().getMaximumPoolSize();
    }

    @Override
    public int getMinPoolSize() {
        return configuration.getPoolConfig().getMinimumIdle();
    }

    @Override
    public Duration getConnectionTimeout() {
        return Duration.ofMillis(configuration.getPoolConfig().getConnectionTimeout());
    }

    @Override
    public Duration getIdleTimeout() {
        return Duration.ofMillis(configuration.getPoolConfig().getIdleTimeout());
    }

    @Override
    public Duration getMaxLifetime() {
        return Duration.ofMillis(configuration.getPoolConfig().getMaxLifetime());
    }
    
    @Override
    public String getInstanceId() {
        return configuration.getMetricsConfig().getInstanceId();
    }
    
    @Override
    public boolean isMetricsEnabled() {
        return configuration.getMetricsConfig().isEnabled();
    }
    
    @Override
    public boolean isHealthChecksEnabled() {
        return configuration.getBoolean("peegeeq.health.enabled", true);
    }

    @Override
    public Duration getHealthCheckInterval() {
        return configuration.getDuration("peegeeq.health.check-interval", Duration.ofSeconds(30));
    }

    @Override
    public boolean isAutoMigrationEnabled() {
        return configuration.getBoolean("peegeeq.migration.enabled", true);
    }
    
    @Override
    public Map<String, Object> getAdditionalProperties() {
        return new HashMap<>(additionalProperties);
    }
    
    @Override
    public Object getProperty(String key) {
        return additionalProperties.get(key);
    }
    
    @Override
    public <T> T getProperty(String key, T defaultValue) {
        Object value = additionalProperties.get(key);
        if (value == null) {
            return defaultValue;
        }
        
        try {
            return (T) value;
        } catch (ClassCastException e) {
            logger.warn("Property {} has wrong type, returning default value", key, e);
            return defaultValue;
        }
    }
    
    /**
     * Sets an additional property.
     * 
     * @param key The property key
     * @param value The property value
     */
    public void setProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }
    
    /**
     * Gets the underlying PeeGeeQConfiguration.
     * This method is provided for backward compatibility.
     * 
     * @return The underlying configuration
     */
    public PeeGeeQConfiguration getUnderlyingConfiguration() {
        return configuration;
    }
}
