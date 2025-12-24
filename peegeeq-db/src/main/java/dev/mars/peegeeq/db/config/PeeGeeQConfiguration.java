package dev.mars.peegeeq.db.config;

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


import dev.mars.peegeeq.api.database.NoticeHandlerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/**
 * Comprehensive configuration management for PeeGeeQ.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PeeGeeQConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQConfiguration.class);
    
    private final Properties properties;
    private final String profile;
    
    public PeeGeeQConfiguration() {
        this(getActiveProfile());
    }
    
    public PeeGeeQConfiguration(String profile) {
        this.profile = profile;
        this.properties = loadProperties(profile);
        validateConfiguration();
        logger.info("Loaded PeeGeeQ configuration for profile: {}", profile);
    }
    
    /**
     * Constructor for programmatic configuration with explicit database settings.
     * This constructor is used internally by PeeGeeQDatabaseSetupService to avoid
     * System property pollution that would cause concurrent setup operations to
     * interfere with each other.
     * 
     * @param profile the configuration profile to use
     * @param dbHost database host
     * @param dbPort database port
     * @param dbName database name
     * @param dbUsername database username
     * @param dbPassword database password
     * @param dbSchema database schema (defaults to "peegeeq" if null)
     */
    public PeeGeeQConfiguration(String profile, String dbHost, int dbPort, String dbName, 
                                String dbUsername, String dbPassword, String dbSchema) {
        this.profile = profile;
        this.properties = loadProperties(profile);
        
        // Override database properties programmatically without polluting System properties
        properties.setProperty("peegeeq.database.host", dbHost);
        properties.setProperty("peegeeq.database.port", String.valueOf(dbPort));
        properties.setProperty("peegeeq.database.name", dbName);
        properties.setProperty("peegeeq.database.username", dbUsername);
        properties.setProperty("peegeeq.database.password", dbPassword);
        if (dbSchema != null && !dbSchema.isEmpty()) {
            properties.setProperty("peegeeq.database.schema", dbSchema);
        }
        
        validateConfiguration();
        logger.info("Loaded PeeGeeQ configuration for profile: {} with explicit database config", profile);
    }
    
    private static String getActiveProfile() {
        return System.getProperty("peegeeq.profile", 
               System.getenv("PEEGEEQ_PROFILE") != null ? System.getenv("PEEGEEQ_PROFILE") : "default");
    }
    
    private Properties loadProperties(String profile) {
        Properties props = new Properties();
        
        // Load default properties first
        loadPropertiesFromResource(props, "/peegeeq-default.properties");
        
        // Load profile-specific properties
        if (!"default".equals(profile)) {
            loadPropertiesFromResource(props, "/peegeeq-" + profile + ".properties");
        }

        // Override with environment variables (convert to property format)
        // Note: Apply env first, then apply system properties AFTER so that test code can override via -D
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("PEEGEEQ_")) {
                String propKey = key.toLowerCase().replace("_", ".");
                props.setProperty(propKey, value);
            }
        });

        // Finally, override with system properties (only peegeeq.*)
        // This ensures test code using System.setProperty wins over env (common testing pattern)
        System.getProperties().forEach((key, value) -> {
            String keyStr = key.toString();
            if (keyStr.startsWith("peegeeq.")) {
                props.setProperty(keyStr, value.toString());
            }
        });

        return props;
    }
    
    private void loadPropertiesFromResource(Properties props, String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(is);
                logger.debug("Loaded properties from: {}", resourcePath);
            } else {
                logger.debug("Properties file not found: {}", resourcePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to load properties from: {}", resourcePath, e);
        }
    }
    
    private void validateConfiguration() {
        List<String> errors = new ArrayList<>();
        
        // Database configuration validation
        validateDatabaseConfig(errors);
        
        // Queue configuration validation
        validateQueueConfig(errors);
        
        // Metrics configuration validation
        validateMetricsConfig(errors);
        
        // Circuit breaker configuration validation
        validateCircuitBreakerConfig(errors);
        
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuration validation failed: " + String.join(", ", errors));
        }
        
        logger.info("Configuration validation passed");
    }
    
    private void validateDatabaseConfig(List<String> errors) {
        if (getString("peegeeq.database.host", "").isEmpty()) {
            errors.add("Database host is required");
        }
        
        int port = getInt("peegeeq.database.port", 5432);
        if (port < 1 || port > 65535) {
            errors.add("Database port must be between 1 and 65535");
        }
        
        if (getString("peegeeq.database.name", "").isEmpty()) {
            errors.add("Database name is required");
        }
        
        if (getString("peegeeq.database.username", "").isEmpty()) {
            errors.add("Database username is required");
        }
        
        // Connection pool validation
        int minPoolSize = getInt("peegeeq.database.pool.min-size", 5);
        int maxPoolSize = getInt("peegeeq.database.pool.max-size", 10);
        
        if (minPoolSize < 1) {
            errors.add("Minimum pool size must be at least 1");
        }
        
        if (maxPoolSize < minPoolSize) {
            errors.add("Maximum pool size must be greater than or equal to minimum pool size");
        }
    }
    
    private void validateQueueConfig(List<String> errors) {
        int maxRetries = getInt("peegeeq.queue.max-retries", 3);
        if (maxRetries < 0) {
            errors.add("Max retries must be non-negative");
        }

        long visibilityTimeoutMs = getLong("peegeeq.queue.visibility-timeout-ms", 30000);
        if (visibilityTimeoutMs < 1000) {
            errors.add("Visibility timeout must be at least 1000ms");
        }

        int batchSize = getInt("peegeeq.queue.batch-size", 10);
        if (batchSize < 1 || batchSize > 1000) {
            errors.add("Batch size must be between 1 and 1000");
        }

        // Validate recovery configuration
        boolean recoveryEnabled = getBoolean("peegeeq.queue.recovery.enabled", true);
        if (recoveryEnabled) {
            Duration processingTimeout = getDuration("peegeeq.queue.recovery.processing-timeout", Duration.ofMinutes(5));
            if (processingTimeout.toMillis() < 60000) { // At least 1 minute
                errors.add("Recovery processing timeout must be at least 1 minute");
            }

            Duration checkInterval = getDuration("peegeeq.queue.recovery.check-interval", Duration.ofMinutes(10));
            if (checkInterval.toMillis() < 60000) { // At least 1 minute
                errors.add("Recovery check interval must be at least 1 minute");
            }

            // Check interval should be longer than processing timeout to avoid conflicts
            if (checkInterval.toMillis() <= processingTimeout.toMillis()) {
                errors.add("Recovery check interval should be longer than processing timeout");
            }
        }
    }
    
    private void validateMetricsConfig(List<String> errors) {
        boolean metricsEnabled = getBoolean("peegeeq.metrics.enabled", true);
        if (metricsEnabled) {
            long reportingIntervalMs = getLong("peegeeq.metrics.reporting-interval-ms", 60000);
            if (reportingIntervalMs < 1000) {
                errors.add("Metrics reporting interval must be at least 1000ms");
            }
        }
    }
    
    private void validateCircuitBreakerConfig(List<String> errors) {
        boolean circuitBreakerEnabled = getBoolean("peegeeq.circuit-breaker.enabled", true);
        if (circuitBreakerEnabled) {
            int failureThreshold = getInt("peegeeq.circuit-breaker.failure-threshold", 5);
            if (failureThreshold < 1) {
                errors.add("Circuit breaker failure threshold must be at least 1");
            }
            
            long waitDurationMs = getLong("peegeeq.circuit-breaker.wait-duration-ms", 60000);
            if (waitDurationMs < 1000) {
                errors.add("Circuit breaker wait duration must be at least 1000ms");
            }
        }
    }
    
    // Configuration getters with defaults and validation
    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    public String getString(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Required configuration property not found: " + key);
        }
        return value;
    }
    
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    public long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid long value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    public Duration getDuration(String key, Duration defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Duration.parse(value);
        } catch (Exception e) {
            logger.warn("Invalid duration value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    // Specific configuration builders
    public PgConnectionConfig getDatabaseConfig() {
        return new PgConnectionConfig.Builder()
            .host(getString("peegeeq.database.host", "localhost"))
            .port(getInt("peegeeq.database.port", 5432))
            .database(getString("peegeeq.database.name", "peegeeq"))
            .username(getString("peegeeq.database.username", "peegeeq"))
            .password(getString("peegeeq.database.password", ""))
            .schema(getString("peegeeq.database.schema", "public"))
            .sslEnabled(getBoolean("peegeeq.database.ssl.enabled", false))
            .build();
    }
    
    public PgPoolConfig getPoolConfig() {
        return new PgPoolConfig.Builder()
            .maxSize(getInt("peegeeq.database.pool.max-size", 32))
            .maxWaitQueueSize(getInt("peegeeq.database.pool.max-wait-queue-size", 128))
            .connectionTimeout(java.time.Duration.ofMillis(getLong("peegeeq.database.pool.connection-timeout-ms", 30000)))
            .idleTimeout(java.time.Duration.ofMillis(getLong("peegeeq.database.pool.idle-timeout-ms", 600000)))
            .shared(getBoolean("peegeeq.database.pool.shared", true))
            .build();
    }
    
    public QueueConfig getQueueConfig() {
        return new QueueConfig(
            getInt("peegeeq.queue.max-retries", 3),
            getDuration("peegeeq.queue.visibility-timeout", Duration.ofSeconds(30)),
            getInt("peegeeq.queue.batch-size", 10),
            getDuration("peegeeq.queue.polling-interval", Duration.ofSeconds(5)),
            getBoolean("peegeeq.queue.dead-letter.enabled", true),
            getInt("peegeeq.queue.priority.default", 5),
            getInt("peegeeq.consumer.threads", 1),
            getBoolean("peegeeq.queue.recovery.enabled", true),
            getDuration("peegeeq.queue.recovery.processing-timeout", Duration.ofMinutes(5)),
            getDuration("peegeeq.queue.recovery.check-interval", Duration.ofMinutes(10))
        );
    }

    public NoticeHandlerConfig getNoticeHandlerConfig() {
        return new NoticeHandlerConfig.Builder()
            .peeGeeQInfoLoggingEnabled(getBoolean("peegeeq.notices.info.enabled", true))
            .peeGeeQInfoLogLevel(getString("peegeeq.notices.info.level", "INFO"))
            .otherNoticesLoggingEnabled(getBoolean("peegeeq.notices.other.enabled", false))
            .otherNoticesLogLevel(getString("peegeeq.notices.other.level", "DEBUG"))
            .metricsEnabled(getBoolean("peegeeq.notices.metrics.enabled", true))
            .build();
    }
    
    public MetricsConfig getMetricsConfig() {
        return new MetricsConfig(
            getBoolean("peegeeq.metrics.enabled", true),
            getDuration("peegeeq.metrics.reporting-interval", Duration.ofMinutes(1)),
            getBoolean("peegeeq.metrics.jvm.enabled", true),
            getBoolean("peegeeq.metrics.database.enabled", true),
            getString("peegeeq.metrics.instance-id", "peegeeq-" + UUID.randomUUID().toString().substring(0, 8))
        );
    }
    
    public CircuitBreakerConfig getCircuitBreakerConfig() {
        return new CircuitBreakerConfig(
            getBoolean("peegeeq.circuit-breaker.enabled", true),
            getInt("peegeeq.circuit-breaker.failure-threshold", 5),
            getDuration("peegeeq.circuit-breaker.wait-duration", Duration.ofMinutes(1)),
            getInt("peegeeq.circuit-breaker.ring-buffer-size", 100),
            getDouble("peegeeq.circuit-breaker.failure-rate-threshold", 50.0)
        );
    }

    public HealthCheckConfig getHealthCheckConfig() {
        return new HealthCheckConfig(
            getBoolean("peegeeq.health-check.enabled", true),
            getBoolean("peegeeq.health-check.queue-checks-enabled", true),
            getDuration("peegeeq.health-check.interval", Duration.ofSeconds(30)),
            getDuration("peegeeq.health-check.timeout", Duration.ofSeconds(5))
        );
    }
    
    private double getDouble(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid double value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    // Configuration data classes
    public static class QueueConfig {
        private final int maxRetries;
        private final Duration visibilityTimeout;
        private final int batchSize;
        private final Duration pollingInterval;
        private final boolean deadLetterEnabled;
        private final int defaultPriority;
        private final int consumerThreads;
        private final boolean recoveryEnabled;
        private final Duration recoveryProcessingTimeout;
        private final Duration recoveryCheckInterval;

        public QueueConfig(int maxRetries, Duration visibilityTimeout, int batchSize,
                          Duration pollingInterval, boolean deadLetterEnabled, int defaultPriority,
                          int consumerThreads, boolean recoveryEnabled, Duration recoveryProcessingTimeout,
                          Duration recoveryCheckInterval) {
            this.maxRetries = maxRetries;
            this.visibilityTimeout = visibilityTimeout;
            this.batchSize = batchSize;
            this.pollingInterval = pollingInterval;
            this.deadLetterEnabled = deadLetterEnabled;
            this.defaultPriority = defaultPriority;
            this.consumerThreads = Math.max(1, consumerThreads); // Ensure at least 1 thread
            this.recoveryEnabled = recoveryEnabled;
            this.recoveryProcessingTimeout = recoveryProcessingTimeout;
            this.recoveryCheckInterval = recoveryCheckInterval;
        }
        
        public int getMaxRetries() { return maxRetries; }
        public Duration getVisibilityTimeout() { return visibilityTimeout; }
        public int getBatchSize() { return batchSize; }
        public Duration getPollingInterval() { return pollingInterval; }
        public boolean isDeadLetterEnabled() { return deadLetterEnabled; }
        public int getDefaultPriority() { return defaultPriority; }
        public int getConsumerThreads() { return consumerThreads; }
        public boolean isRecoveryEnabled() { return recoveryEnabled; }
        public Duration getRecoveryProcessingTimeout() { return recoveryProcessingTimeout; }
        public Duration getRecoveryCheckInterval() { return recoveryCheckInterval; }
    }
    
    public static class MetricsConfig {
        private final boolean enabled;
        private final Duration reportingInterval;
        private final boolean jvmMetricsEnabled;
        private final boolean databaseMetricsEnabled;
        private final String instanceId;
        
        public MetricsConfig(boolean enabled, Duration reportingInterval, boolean jvmMetricsEnabled,
                           boolean databaseMetricsEnabled, String instanceId) {
            this.enabled = enabled;
            this.reportingInterval = reportingInterval;
            this.jvmMetricsEnabled = jvmMetricsEnabled;
            this.databaseMetricsEnabled = databaseMetricsEnabled;
            this.instanceId = instanceId;
        }
        
        public boolean isEnabled() { return enabled; }
        public Duration getReportingInterval() { return reportingInterval; }
        public boolean isJvmMetricsEnabled() { return jvmMetricsEnabled; }
        public boolean isDatabaseMetricsEnabled() { return databaseMetricsEnabled; }
        public String getInstanceId() { return instanceId; }
    }
    
    public static class CircuitBreakerConfig {
        private final boolean enabled;
        private final int failureThreshold;
        private final Duration waitDuration;
        private final int ringBufferSize;
        private final double failureRateThreshold;
        
        public CircuitBreakerConfig(boolean enabled, int failureThreshold, Duration waitDuration,
                                  int ringBufferSize, double failureRateThreshold) {
            this.enabled = enabled;
            this.failureThreshold = failureThreshold;
            this.waitDuration = waitDuration;
            this.ringBufferSize = ringBufferSize;
            this.failureRateThreshold = failureRateThreshold;
        }
        
        public boolean isEnabled() { return enabled; }
        public int getFailureThreshold() { return failureThreshold; }
        public Duration getWaitDuration() { return waitDuration; }
        public int getRingBufferSize() { return ringBufferSize; }
        public double getFailureRateThreshold() { return failureRateThreshold; }
    }

    public static class HealthCheckConfig {
        private final boolean enabled;
        private final boolean queueChecksEnabled;
        private final Duration interval;
        private final Duration timeout;

        public HealthCheckConfig(boolean enabled, boolean queueChecksEnabled, Duration interval, Duration timeout) {
            this.enabled = enabled;
            this.queueChecksEnabled = queueChecksEnabled;
            this.interval = interval;
            this.timeout = timeout;
        }

        public boolean isEnabled() { return enabled; }
        public boolean isQueueChecksEnabled() { return queueChecksEnabled; }
        public Duration getInterval() { return interval; }
        public Duration getTimeout() { return timeout; }
    }

    public String getProfile() { return profile; }
    public Properties getProperties() { return new Properties(properties); }
}
