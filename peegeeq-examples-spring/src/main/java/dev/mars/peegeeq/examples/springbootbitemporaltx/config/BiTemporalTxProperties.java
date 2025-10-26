package dev.mars.peegeeq.examples.springbootbitemporaltx.config;

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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Configuration properties for Spring Boot Bi-Temporal Transaction Coordination.
 * 
 * This class defines all configuration properties needed for advanced bi-temporal
 * event store patterns with multi-event store transaction coordination.
 * 
 * <h2>Configuration Sections</h2>
 * 
 * <h3>Database Configuration</h3>
 * <ul>
 *   <li><b>Connection Settings</b> - Host, port, database name, credentials</li>
 *   <li><b>Schema Management</b> - Schema name and initialization settings</li>
 *   <li><b>SSL Configuration</b> - Security settings for production</li>
 * </ul>
 * 
 * <h3>Connection Pool Configuration</h3>
 * <ul>
 *   <li><b>Pool Sizing</b> - Min/max connections for optimal performance</li>
 *   <li><b>Timeout Settings</b> - Connection and idle timeouts</li>
 *   <li><b>Health Checks</b> - Connection validation and monitoring</li>
 * </ul>
 * 
 * <h3>Transaction Configuration</h3>
 * <ul>
 *   <li><b>Timeout Settings</b> - Transaction timeout for multi-store operations</li>
 *   <li><b>Retry Logic</b> - Retry attempts for transient failures</li>
 *   <li><b>Isolation Levels</b> - Transaction isolation configuration</li>
 * </ul>
 * 
 * <h3>Event Store Configuration</h3>
 * <ul>
 *   <li><b>Storage Settings</b> - Compression and retention policies</li>
 *   <li><b>Query Optimization</b> - Index and query performance settings</li>
 *   <li><b>Notification Settings</b> - Real-time event notification configuration</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@ConfigurationProperties(prefix = "peegeeq.bitemporal")
public class BiTemporalTxProperties {
    
    /**
     * PeeGeeQ configuration profile (e.g., "development", "production")
     */
    private String profile = "development";
    
    /**
     * Database configuration settings
     */
    @NestedConfigurationProperty
    private DatabaseProperties database = new DatabaseProperties();
    
    /**
     * Connection pool configuration settings
     */
    @NestedConfigurationProperty
    private PoolProperties pool = new PoolProperties();
    
    /**
     * Transaction coordination configuration settings
     */
    @NestedConfigurationProperty
    private TransactionProperties transaction = new TransactionProperties();
    
    /**
     * Event store specific configuration settings
     */
    @NestedConfigurationProperty
    private EventStoreProperties eventStore = new EventStoreProperties();
    
    // Getters and setters
    
    public String getProfile() {
        return profile;
    }
    
    public void setProfile(String profile) {
        this.profile = profile;
    }
    
    public DatabaseProperties getDatabase() {
        return database;
    }
    
    public void setDatabase(DatabaseProperties database) {
        this.database = database;
    }
    
    public PoolProperties getPool() {
        return pool;
    }
    
    public void setPool(PoolProperties pool) {
        this.pool = pool;
    }
    
    public TransactionProperties getTransaction() {
        return transaction;
    }
    
    public void setTransaction(TransactionProperties transaction) {
        this.transaction = transaction;
    }
    
    public EventStoreProperties getEventStore() {
        return eventStore;
    }
    
    public void setEventStore(EventStoreProperties eventStore) {
        this.eventStore = eventStore;
    }
    
    /**
     * Database connection configuration properties.
     */
    public static class DatabaseProperties {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq_bitemporal";
        private String username = "peegeeq";
        private String password = "peegeeq";
        private String schema = "public";
        private boolean sslEnabled = false;
        
        // Getters and setters
        
        public String getHost() {
            return host;
        }
        
        public void setHost(String host) {
            this.host = host;
        }
        
        public int getPort() {
            return port;
        }
        
        public void setPort(int port) {
            this.port = port;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getPassword() {
            return password;
        }
        
        public void setPassword(String password) {
            this.password = password;
        }
        
        public String getSchema() {
            return schema;
        }
        
        public void setSchema(String schema) {
            this.schema = schema;
        }
        
        public boolean isSslEnabled() {
            return sslEnabled;
        }
        
        public void setSslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
        }
    }
    
    /**
     * Connection pool configuration properties.
     */
    public static class PoolProperties {
        private int maxSize = 20;
        private int minSize = 5;
        private Duration connectionTimeout = Duration.ofSeconds(30);
        private Duration idleTimeout = Duration.ofMinutes(10);
        private Duration maxLifetime = Duration.ofMinutes(30);
        
        // Getters and setters
        
        public int getMaxSize() {
            return maxSize;
        }
        
        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
        
        public int getMinSize() {
            return minSize;
        }
        
        public void setMinSize(int minSize) {
            this.minSize = minSize;
        }
        
        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }
        
        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
        
        public Duration getIdleTimeout() {
            return idleTimeout;
        }
        
        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
        
        public Duration getMaxLifetime() {
            return maxLifetime;
        }
        
        public void setMaxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
        }
    }
    
    /**
     * Transaction coordination configuration properties.
     */
    public static class TransactionProperties {
        private Duration timeout = Duration.ofMinutes(5);
        private int retryAttempts = 3;
        private Duration retryDelay = Duration.ofMillis(100);
        private String isolationLevel = "READ_COMMITTED";
        
        // Getters and setters
        
        public Duration getTimeout() {
            return timeout;
        }
        
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
        
        public int getRetryAttempts() {
            return retryAttempts;
        }
        
        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }
        
        public Duration getRetryDelay() {
            return retryDelay;
        }
        
        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }
        
        public String getIsolationLevel() {
            return isolationLevel;
        }
        
        public void setIsolationLevel(String isolationLevel) {
            this.isolationLevel = isolationLevel;
        }
    }
    
    /**
     * Event store specific configuration properties.
     */
    public static class EventStoreProperties {
        private boolean compressionEnabled = true;
        private Duration retentionPeriod = Duration.ofDays(365);
        private int batchSize = 100;
        private boolean notificationsEnabled = true;
        
        // Getters and setters
        
        public boolean isCompressionEnabled() {
            return compressionEnabled;
        }
        
        public void setCompressionEnabled(boolean compressionEnabled) {
            this.compressionEnabled = compressionEnabled;
        }
        
        public Duration getRetentionPeriod() {
            return retentionPeriod;
        }
        
        public void setRetentionPeriod(Duration retentionPeriod) {
            this.retentionPeriod = retentionPeriod;
        }
        
        public int getBatchSize() {
            return batchSize;
        }
        
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
        
        public boolean isNotificationsEnabled() {
            return notificationsEnabled;
        }
        
        public void setNotificationsEnabled(boolean notificationsEnabled) {
            this.notificationsEnabled = notificationsEnabled;
        }
    }
}
