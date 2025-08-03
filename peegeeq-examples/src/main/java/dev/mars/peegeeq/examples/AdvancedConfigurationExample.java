package dev.mars.peegeeq.examples;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Advanced Configuration Management Example demonstrating production-ready configuration patterns.
 * 
 * This example covers:
 * - Environment-specific configurations (development, staging, production)
 * - External configuration management (properties files, environment variables)
 * - Database connection pooling and tuning
 * - Monitoring integration (Prometheus/Grafana ready metrics)
 * - Configuration validation and best practices
 * - Runtime configuration updates
 * 
 * Configuration Hierarchy (highest to lowest priority):
 * 1. System properties (-Dproperty=value)
 * 2. Environment variables
 * 3. External configuration files
 * 4. Application defaults
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
public class AdvancedConfigurationExample {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedConfigurationExample.class);
    
    // Configuration keys
    private static final String ENV_KEY = "PEEGEEQ_ENVIRONMENT";
    private static final String CONFIG_FILE_KEY = "PEEGEEQ_CONFIG_FILE";
    private static final String DB_URL_KEY = "PEEGEEQ_DB_URL";
    private static final String DB_USERNAME_KEY = "PEEGEEQ_DB_USERNAME";
    private static final String DB_PASSWORD_KEY = "PEEGEEQ_DB_PASSWORD";
    private static final String MONITORING_ENABLED_KEY = "PEEGEEQ_MONITORING_ENABLED";
    
    public static void main(String[] args) throws Exception {
        logger.info("=== Advanced Configuration Management Example ===");
        logger.info("This example demonstrates production-ready configuration patterns for PeeGeeQ");
        
        // Demonstrate different configuration approaches
        demonstrateEnvironmentSpecificConfiguration();
        demonstrateExternalConfigurationManagement();
        demonstrateDatabaseConnectionPooling();
        demonstrateMonitoringIntegration();
        demonstrateConfigurationValidation();
        demonstrateRuntimeConfigurationUpdates();
        
        logger.info("Advanced Configuration Management Example completed successfully!");
    }
    
    /**
     * Demonstrates environment-specific configuration patterns.
     */
    private static void demonstrateEnvironmentSpecificConfiguration() {
        logger.info("\n=== ENVIRONMENT-SPECIFIC CONFIGURATION ===");
        
        // Simulate different environments
        String[] environments = {"development", "staging", "production"};
        
        for (String environment : environments) {
            logger.info("\n--- {} Environment Configuration ---", environment.toUpperCase());
            
            logger.info("✅ {} Configuration Properties:", environment);
            
            // Database configuration
            logger.info("   Database Configuration:");
            logger.info("     peegeeq.database.host={}", getDatabaseHost(environment));
            logger.info("     peegeeq.database.port={}", getDatabasePort(environment));
            logger.info("     peegeeq.database.name={}", getDatabaseName(environment));
            logger.info("     peegeeq.database.ssl.enabled={}", isSslEnabled(environment));
            
            // Pool configuration
            logger.info("   Connection Pool Configuration:");
            logger.info("     peegeeq.pool.min-size={}", getMinPoolSize(environment));
            logger.info("     peegeeq.pool.max-size={}", getMaxPoolSize(environment));
            logger.info("     peegeeq.pool.connection-timeout-ms={}", getConnectionTimeout(environment));
            logger.info("     peegeeq.pool.idle-timeout-ms={}", getIdleTimeout(environment));
            logger.info("     peegeeq.pool.max-lifetime-ms={}", getMaxLifetime(environment));
            
            // Queue configuration
            logger.info("   Queue Configuration:");
            logger.info("     peegeeq.queue.max-retries={}", getMaxRetries(environment));
            logger.info("     peegeeq.queue.visibility-timeout-ms={}", getVisibilityTimeout(environment));
            logger.info("     peegeeq.queue.batch-size={}", getBatchSize(environment));
            logger.info("     peegeeq.queue.polling-interval-ms={}", getPollingInterval(environment));
            
            // Monitoring configuration
            logger.info("   Monitoring Configuration:");
            logger.info("     peegeeq.monitoring.enabled={}", isMonitoringEnabled(environment));
            logger.info("     peegeeq.metrics.prometheus.enabled={}", isPrometheusEnabled(environment));
            logger.info("     peegeeq.logging.level={}", getLogLevel(environment));
            
            logger.info("   Environment Profile: {}", environment);
            logger.info("   Retry Policy: {}", getRetryPolicy(environment));
        }
    }
    
    /**
     * Demonstrates external configuration management patterns.
     */
    private static void demonstrateExternalConfigurationManagement() throws Exception {
        logger.info("\n=== EXTERNAL CONFIGURATION MANAGEMENT ===");
        
        // 1. Properties file configuration
        logger.info("\n--- Properties File Configuration ---");
        Properties props = loadExternalProperties();
        if (props != null) {
            logger.info("✅ Loaded external properties:");
            props.forEach((key, value) -> {
                String displayValue = key.toString().toLowerCase().contains("password") ? 
                    maskSensitiveInfo(value.toString()) : value.toString();
                logger.info("   {}: {}", key, displayValue);
            });
        }
        
        // 2. Environment variables configuration
        logger.info("\n--- Environment Variables Configuration ---");
        demonstrateEnvironmentVariables();
        
        // 3. System properties configuration
        logger.info("\n--- System Properties Configuration ---");
        demonstrateSystemProperties();
        
        // 4. Configuration hierarchy demonstration
        logger.info("\n--- Configuration Hierarchy Demonstration ---");
        demonstrateConfigurationHierarchy();
    }
    
    /**
     * Loads external properties file.
     */
    private static Properties loadExternalProperties() {
        String configFile = System.getProperty(CONFIG_FILE_KEY, System.getenv(CONFIG_FILE_KEY));
        if (configFile == null) {
            configFile = "peegeeq-config.properties"; // Default
        }
        
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
            logger.info("✅ Loaded configuration from: {}", configFile);
            return props;
        } catch (IOException e) {
            logger.info("ℹ️ External config file not found: {} (using defaults)", configFile);
            return null;
        }
    }
    
    /**
     * Demonstrates environment variables configuration.
     */
    private static void demonstrateEnvironmentVariables() {
        logger.info("✅ Environment Variables Configuration:");
        
        // Simulate environment variables for demo
        System.setProperty(DB_URL_KEY, "jdbc:postgresql://localhost:5432/peegeeq");
        System.setProperty(DB_USERNAME_KEY, "peegeeq_user");
        System.setProperty(DB_PASSWORD_KEY, "secure_password");
        System.setProperty(MONITORING_ENABLED_KEY, "true");
        
        String dbUrl = getConfigValue(DB_URL_KEY, "jdbc:postgresql://localhost:5432/peegeeq");
        String dbUsername = getConfigValue(DB_USERNAME_KEY, "peegeeq");
        String dbPassword = getConfigValue(DB_PASSWORD_KEY, "password");
        boolean monitoringEnabled = Boolean.parseBoolean(getConfigValue(MONITORING_ENABLED_KEY, "false"));
        
        logger.info("   Database URL: {}", maskSensitiveInfo(dbUrl));
        logger.info("   Database Username: {}", dbUsername);
        logger.info("   Database Password: {}", maskSensitiveInfo(dbPassword));
        logger.info("   Monitoring Enabled: {}", monitoringEnabled);
        
        // Clean up
        System.clearProperty(DB_URL_KEY);
        System.clearProperty(DB_USERNAME_KEY);
        System.clearProperty(DB_PASSWORD_KEY);
        System.clearProperty(MONITORING_ENABLED_KEY);
    }
    
    /**
     * Demonstrates system properties configuration.
     */
    private static void demonstrateSystemProperties() {
        logger.info("✅ System Properties Configuration:");
        logger.info("   Usage: java -Dpeegeeq.database.host=... -Dpeegeeq.monitoring.enabled=true ...");
        
        // Example system properties
        String[] systemProps = {
            "peegeeq.database.host=prod-db.example.com",
            "peegeeq.database.port=5432",
            "peegeeq.database.name=peegeeq_prod",
            "peegeeq.database.username=peegeeq_user",
            "peegeeq.database.password=secure_password",
            "peegeeq.database.pool.max-size=20",
            "peegeeq.queue.max-retries=5"
        };
        
        logger.info("   Example system properties:");
        for (String prop : systemProps) {
            String[] parts = prop.split("=", 2);
            String key = parts[0];
            String value = parts[1];
            String displayValue = key.toLowerCase().contains("password") ? 
                maskSensitiveInfo(value) : value;
            logger.info("     -D{}={}", key, displayValue);
        }
    }
    
    /**
     * Demonstrates configuration hierarchy (precedence order).
     */
    private static void demonstrateConfigurationHierarchy() {
        logger.info("✅ Configuration Hierarchy (highest to lowest priority):");
        logger.info("   1. System Properties (-Dproperty=value)");
        logger.info("   2. Environment Variables (PEEGEEQ_*)");
        logger.info("   3. External Configuration Files");
        logger.info("   4. Application Defaults");
        
        // Demonstrate precedence
        String testKey = "peegeeq.test.value";
        
        // Set different values at different levels
        System.setProperty(testKey, "system-property-value");
        System.setProperty("env." + testKey, "environment-variable-value"); // Simulate env var
        
        String finalValue = getConfigValue(testKey, "application-default-value");
        logger.info("   Final resolved value for '{}': {}", testKey, finalValue);
        logger.info("   (System property takes precedence over other sources)");
        
        // Clean up
        System.clearProperty(testKey);
        System.clearProperty("env." + testKey);
    }
    
    /**
     * Demonstrates database connection pooling and tuning.
     */
    private static void demonstrateDatabaseConnectionPooling() {
        logger.info("\n=== DATABASE CONNECTION POOLING AND TUNING ===");
        
        // Different connection pool configurations
        ConnectionPoolConfig[] poolConfigs = {
            new ConnectionPoolConfig("Small Load", 5, 2000, 300000, 1800000),
            new ConnectionPoolConfig("Medium Load", 15, 3000, 600000, 3600000),
            new ConnectionPoolConfig("High Load", 30, 1000, 900000, 7200000)
        };
        
        for (ConnectionPoolConfig poolConfig : poolConfigs) {
            logger.info("\n--- {} Configuration ---", poolConfig.name);
            
            logger.info("✅ Connection Pool Settings:");
            logger.info("   Pool Size: {} connections", poolConfig.poolSize);
            logger.info("   Connection Timeout: {}ms", poolConfig.connectionTimeoutMs);
            logger.info("   Idle Timeout: {}ms ({} minutes)", 
                       poolConfig.idleTimeoutMs, poolConfig.idleTimeoutMs / 60000);
            logger.info("   Max Lifetime: {}ms ({} minutes)", 
                       poolConfig.maxLifetimeMs, poolConfig.maxLifetimeMs / 60000);
            
            // Show how this would be configured
            logger.info("   Configuration properties:");
            logger.info("     peegeeq.database.pool.min-size={}", Math.max(1, poolConfig.poolSize / 4));
            logger.info("     peegeeq.database.pool.max-size={}", poolConfig.poolSize);
            logger.info("     peegeeq.database.pool.connection-timeout-ms={}", poolConfig.connectionTimeoutMs);
            logger.info("     peegeeq.database.pool.idle-timeout-ms={}", poolConfig.idleTimeoutMs);
            logger.info("     peegeeq.database.pool.max-lifetime-ms={}", poolConfig.maxLifetimeMs);
        }
        
        // Connection pool tuning recommendations
        logger.info("\n--- Connection Pool Tuning Recommendations ---");
        logger.info("✅ Pool Size Guidelines:");
        logger.info("   - Development: 5-10 connections");
        logger.info("   - Staging: 10-20 connections");
        logger.info("   - Production: 20-50 connections (based on load)");
        logger.info("   - Formula: (CPU cores * 2) + disk spindles");
        
        logger.info("✅ Timeout Guidelines:");
        logger.info("   - Connection Timeout: 1-5 seconds");
        logger.info("   - Idle Timeout: 5-15 minutes");
        logger.info("   - Max Lifetime: 30 minutes - 2 hours");
        
        logger.info("✅ Monitoring Metrics:");
        logger.info("   - Active connections");
        logger.info("   - Idle connections");
        logger.info("   - Connection wait time");
        logger.info("   - Connection creation rate");
        logger.info("   - Connection pool exhaustion events");
    }
    
    /**
     * Demonstrates monitoring integration with Prometheus/Grafana.
     */
    private static void demonstrateMonitoringIntegration() {
        logger.info("\n=== MONITORING INTEGRATION (PROMETHEUS/GRAFANA) ===");
        
        logger.info("✅ Prometheus Metrics Integration:");
        logger.info("   Configuration properties:");
        logger.info("     peegeeq.monitoring.enabled=true");
        logger.info("     peegeeq.metrics.prometheus.enabled=true");
        logger.info("     peegeeq.metrics.jvm.enabled=true");
        logger.info("     peegeeq.metrics.system.enabled=true");
        
        logger.info("   Metrics Endpoint: /actuator/prometheus (in Spring Boot)");
        logger.info("   Scrape Interval: 15s (recommended)");
        
        // Monitoring best practices
        logger.info("\n--- Monitoring Best Practices ---");
        logger.info("✅ Key Metrics to Monitor:");
        logger.info("   - Message throughput (messages/second)");
        logger.info("   - Message latency (processing time)");
        logger.info("   - Queue depth (pending messages)");
        logger.info("   - Error rate (failed messages/total messages)");
        logger.info("   - Connection pool utilization");
        logger.info("   - Database connection health");
        
        logger.info("✅ Alerting Thresholds:");
        logger.info("   - Queue depth > 1000 messages");
        logger.info("   - Error rate > 5%");
        logger.info("   - Message latency > 10 seconds");
        logger.info("   - Connection pool utilization > 80%");
        
        logger.info("✅ Grafana Dashboard Panels:");
        logger.info("   - Message throughput over time");
        logger.info("   - Queue depth by queue name");
        logger.info("   - Error rate percentage");
        logger.info("   - Connection pool metrics");
        logger.info("   - System resource utilization");
        
        logger.info("   💡 Use this endpoint in Prometheus configuration:");
        logger.info("     scrape_configs:");
        logger.info("       - job_name: 'peegeeq'");
        logger.info("         static_configs:");
        logger.info("           - targets: ['localhost:8080']");
        logger.info("         metrics_path: '/actuator/prometheus'");
        logger.info("         scrape_interval: 15s");
    }
    
    /**
     * Demonstrates configuration validation.
     */
    private static void demonstrateConfigurationValidation() {
        logger.info("\n=== CONFIGURATION VALIDATION ===");
        
        logger.info("✅ Configuration Validation Patterns:");
        logger.info("   - Required vs Optional parameters");
        logger.info("   - Value range validation");
        logger.info("   - Format validation (URLs, timeouts)");
        logger.info("   - Cross-parameter validation");
        logger.info("   - Environment-specific validation rules");
        
        // Example validation scenarios
        validateConnectionPoolSettings(5, 2000, 300000);
        validateConnectionPoolSettings(0, 2000, 300000); // Invalid
        validateConnectionPoolSettings(100, 100, 300000); // Warning
        
        logger.info("✅ Validation Best Practices:");
        logger.info("   - Fail fast on invalid configuration");
        logger.info("   - Provide clear error messages");
        logger.info("   - Log configuration values at startup");
        logger.info("   - Validate configuration in tests");
        logger.info("   - Use configuration schemas where possible");
        
        logger.info("✅ PeeGeeQ Built-in Validation:");
        logger.info("   - Database connection parameters");
        logger.info("   - Pool size limits and ratios");
        logger.info("   - Timeout value ranges");
        logger.info("   - Queue configuration constraints");
        logger.info("   - Circuit breaker thresholds");
    }
    
    /**
     * Demonstrates runtime configuration updates.
     */
    private static void demonstrateRuntimeConfigurationUpdates() {
        logger.info("\n=== RUNTIME CONFIGURATION UPDATES ===");
        
        logger.info("✅ Runtime Update Capabilities:");
        logger.info("   - Log level changes");
        logger.info("   - Connection pool size adjustments");
        logger.info("   - Timeout value modifications");
        logger.info("   - Monitoring toggle");
        logger.info("   - Feature flag updates");
        
        logger.info("✅ Update Mechanisms:");
        logger.info("   - JMX MBeans for management");
        logger.info("   - REST endpoints for configuration");
        logger.info("   - Configuration file watching");
        logger.info("   - Environment variable monitoring");
        logger.info("   - External configuration services");
        
        logger.info("✅ Safety Considerations:");
        logger.info("   - Validate changes before applying");
        logger.info("   - Gradual rollout of changes");
        logger.info("   - Rollback capability");
        logger.info("   - Audit trail of configuration changes");
        logger.info("   - Impact assessment before updates");
        
        logger.info("⚠️ Non-updatable Configuration:");
        logger.info("   - Database connection URL");
        logger.info("   - Core threading model");
        logger.info("   - Security credentials (require restart)");
        logger.info("   - JVM-level settings");
    }
    
    // Helper methods and configuration getters
    
    private static String getConfigValue(String key, String defaultValue) {
        // Check system properties first
        String value = System.getProperty(key);
        if (value != null) {
            return value;
        }
        
        // Check environment variables
        value = System.getenv(key);
        if (value != null) {
            return value;
        }
        
        // Check simulated environment variables (for demo)
        value = System.getProperty("env." + key);
        if (value != null) {
            return value;
        }
        
        // Return default
        return defaultValue;
    }
    
    private static String maskSensitiveInfo(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
    
    // Environment-specific configuration getters
    
    private static String getDatabaseHost(String environment) {
        switch (environment) {
            case "development": return "localhost";
            case "staging": return "staging-db.example.com";
            case "production": return "prod-db.example.com";
            default: return "localhost";
        }
    }
    
    private static int getDatabasePort(String environment) {
        return 5432; // Same for all environments
    }
    
    private static String getDatabaseName(String environment) {
        switch (environment) {
            case "development": return "peegeeq_dev";
            case "staging": return "peegeeq_staging";
            case "production": return "peegeeq_prod";
            default: return "peegeeq";
        }
    }
    
    private static boolean isSslEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }
    
    private static int getMinPoolSize(String environment) {
        switch (environment) {
            case "development": return 2;
            case "staging": return 5;
            case "production": return 10;
            default: return 2;
        }
    }
    
    private static int getMaxPoolSize(String environment) {
        switch (environment) {
            case "development": return 5;
            case "staging": return 15;
            case "production": return 30;
            default: return 5;
        }
    }
    
    private static int getConnectionTimeout(String environment) {
        switch (environment) {
            case "development": return 5000;
            case "staging": return 3000;
            case "production": return 2000;
            default: return 5000;
        }
    }
    
    private static int getIdleTimeout(String environment) {
        switch (environment) {
            case "development": return 300000; // 5 minutes
            case "staging": return 600000; // 10 minutes
            case "production": return 900000; // 15 minutes
            default: return 300000;
        }
    }
    
    private static int getMaxLifetime(String environment) {
        switch (environment) {
            case "development": return 1800000; // 30 minutes
            case "staging": return 3600000; // 1 hour
            case "production": return 7200000; // 2 hours
            default: return 1800000;
        }
    }
    
    private static int getMaxRetries(String environment) {
        switch (environment) {
            case "development": return 3;
            case "staging": return 3;
            case "production": return 5;
            default: return 3;
        }
    }
    
    private static int getVisibilityTimeout(String environment) {
        switch (environment) {
            case "development": return 30000; // 30 seconds
            case "staging": return 45000; // 45 seconds
            case "production": return 60000; // 60 seconds
            default: return 30000;
        }
    }
    
    private static int getBatchSize(String environment) {
        switch (environment) {
            case "development": return 10;
            case "staging": return 25;
            case "production": return 50;
            default: return 10;
        }
    }
    
    private static int getPollingInterval(String environment) {
        switch (environment) {
            case "development": return 1000; // 1 second
            case "staging": return 750; // 0.75 seconds
            case "production": return 500; // 0.5 seconds
            default: return 1000;
        }
    }
    
    private static boolean isMonitoringEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }
    
    private static boolean isPrometheusEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }
    
    private static String getLogLevel(String environment) {
        switch (environment) {
            case "development": return "DEBUG";
            case "staging": return "INFO";
            case "production": return "WARN";
            default: return "INFO";
        }
    }
    
    private static String getRetryPolicy(String environment) {
        switch (environment) {
            case "development": return "Immediate (no delay)";
            case "staging": return "Exponential backoff (max 3 retries)";
            case "production": return "Exponential backoff (max 5 retries)";
            default: return "Default";
        }
    }
    
    private static void validateConnectionPoolSettings(int poolSize, int connectionTimeout, int idleTimeout) {
        logger.info("   Validating pool settings: size={}, connectionTimeout={}ms, idleTimeout={}ms", 
                   poolSize, connectionTimeout, idleTimeout);
        
        if (poolSize <= 0) {
            logger.error("   ❌ INVALID: Pool size must be greater than 0");
            return;
        }
        
        if (poolSize > 50) {
            logger.warn("   ⚠️ WARNING: Pool size {} is very large, consider reducing", poolSize);
        }
        
        if (connectionTimeout < 1000) {
            logger.warn("   ⚠️ WARNING: Connection timeout {}ms is very low", connectionTimeout);
        }
        
        if (idleTimeout < connectionTimeout) {
            logger.error("   ❌ INVALID: Idle timeout must be greater than connection timeout");
            return;
        }
        
        logger.info("   ✅ VALID: Connection pool settings are acceptable");
    }
    
    /**
     * Connection pool configuration container.
     */
    private static class ConnectionPoolConfig {
        final String name;
        final int poolSize;
        final int connectionTimeoutMs;
        final int idleTimeoutMs;
        final int maxLifetimeMs;
        
        ConnectionPoolConfig(String name, int poolSize, int connectionTimeoutMs, int idleTimeoutMs, int maxLifetimeMs) {
            this.name = name;
            this.poolSize = poolSize;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.idleTimeoutMs = idleTimeoutMs;
            this.maxLifetimeMs = maxLifetimeMs;
        }
    }
}
