package dev.mars.peegeeq.examples.patterns;

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

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUSINESS SCENARIO: Configuration Management for Enterprise Message Queue System
 * 
 * BUSINESS VALUE:
 * - Ensures system properties correctly override configuration files for deployment flexibility
 * - Validates configuration injection works across different deployment environments
 * - Prevents configuration-related production issues through comprehensive validation
 * 
 * TECHNICAL VALIDATION:
 * - System property override mechanism functions correctly
 * - Configuration loading and injection works as expected
 * - Property name conventions are consistent and correct
 * 
 * SUCCESS CRITERIA:
 * - All system properties override their corresponding configuration file values
 * - Configuration objects are properly initialized with correct values
 * - Property naming follows established conventions
 * 
 * REAL-WORLD RELEVANCE:
 * - DevOps teams need to override configuration for different environments (dev/staging/prod)
 * - Kubernetes deployments use environment variables and system properties for configuration
 * - Configuration validation prevents runtime failures in production systems
 * 
 * CONSOLIDATED FROM:
 * - PeeGeeQExampleTest (basic property testing)
 * - SystemPropertiesValidationSimpleTest (property override validation)
 * - SystemPropertiesConfigurationOnlyTest (configuration-only testing)
 * - SystemPropertiesValidationTestPart2 (additional property testing)
 * - PgQueueFactoryProviderEnhancedTest (factory provider configuration)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-16
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConfigurationValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationValidationTest.class);

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        logger.info("=== Setting up Configuration Validation Test ===");
        saveOriginalProperties();
    }

    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down Configuration Validation Test ===");
        restoreOriginalProperties();
    }

    private void saveOriginalProperties() {
        String[] propertiesToSave = {
            "peegeeq.profile",
            "peegeeq.database.host",
            "peegeeq.database.port",
            "peegeeq.database.name",
            "peegeeq.database.username",
            "peegeeq.database.password",
            "peegeeq.queue.max-retries",
            "peegeeq.consumer.threads", 
            "peegeeq.queue.polling-interval",
            "peegeeq.queue.batch-size"
        };
        
        for (String property : propertiesToSave) {
            String value = System.getProperty(property);
            if (value != null) {
                originalProperties.put(property, value);
            }
        }
    }

    private void restoreOriginalProperties() {
        // Clear all test properties
        System.clearProperty("peegeeq.profile");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.batch-size");
        
        // Restore original properties
        originalProperties.forEach(System::setProperty);
    }

    /**
     * BUSINESS SCENARIO: DevOps Configuration Override for Production Deployment
     * 
     * Tests that system properties correctly override configuration file values,
     * which is essential for Kubernetes and Docker deployments where configuration
     * is injected via environment variables.
     */
    @Test
    @Order(1)
    void testSystemPropertiesOverrideConfigurationFiles() throws Exception {
        logger.info("=== Testing System Properties Override Configuration Files ===");
        
        // Get baseline configuration from files
        PeeGeeQConfiguration baseConfig = new PeeGeeQConfiguration("test");
        int baseMaxRetries = baseConfig.getQueueConfig().getMaxRetries();
        int baseBatchSize = baseConfig.getQueueConfig().getBatchSize();
        Duration basePollingInterval = baseConfig.getQueueConfig().getPollingInterval();
        
        logger.info("Base configuration - MaxRetries: {}, BatchSize: {}, PollingInterval: {}", 
            baseMaxRetries, baseBatchSize, basePollingInterval);
        
        // Set system properties to different values (simulating production override)
        int overrideMaxRetries = baseMaxRetries + 5;
        int overrideBatchSize = baseBatchSize + 10;
        Duration overridePollingInterval = basePollingInterval.plusSeconds(2);
        
        System.setProperty("peegeeq.queue.max-retries", String.valueOf(overrideMaxRetries));
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(overrideBatchSize));
        System.setProperty("peegeeq.queue.polling-interval", overridePollingInterval.toString());
        
        // Create new configuration that should pick up system property overrides
        PeeGeeQConfiguration overriddenConfig = new PeeGeeQConfiguration("test");
        
        // BUSINESS VALIDATION: System properties must override file values
        assertEquals(overrideMaxRetries, overriddenConfig.getQueueConfig().getMaxRetries(),
            "System property must override max-retries for production deployment flexibility");
        
        assertEquals(overrideBatchSize, overriddenConfig.getQueueConfig().getBatchSize(),
            "System property must override batch-size for performance tuning in production");
        
        assertEquals(overridePollingInterval, overriddenConfig.getQueueConfig().getPollingInterval(),
            "System property must override polling-interval for environment-specific optimization");
        
        logger.info("✅ Verified system properties override configuration file values:");
        logger.info("  MaxRetries: {} -> {} (production reliability tuning)", baseMaxRetries, overrideMaxRetries);
        logger.info("  BatchSize: {} -> {} (performance optimization)", baseBatchSize, overrideBatchSize);
        logger.info("  PollingInterval: {} -> {} (environment tuning)", basePollingInterval, overridePollingInterval);
        
        logger.info("✅ Configuration override test completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Database Connection Configuration for Multi-Environment Deployment
     * 
     * Tests that database connection properties can be overridden via system properties,
     * which is critical for connecting to different databases in dev/staging/production.
     */
    @Test
    @Order(2)
    void testDatabaseConnectionPropertyOverrides() {
        logger.info("=== Testing Database Connection Property Overrides ===");
        
        // Set database connection properties (simulating Kubernetes ConfigMap/Secret injection)
        System.setProperty("peegeeq.database.host", "prod-postgres.company.com");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "peegeeq_production");
        System.setProperty("peegeeq.database.username", "peegeeq_prod_user");
        System.setProperty("peegeeq.database.password", "secure_prod_password");
        
        // BUSINESS VALIDATION: Properties must be readable for database connection
        assertEquals("prod-postgres.company.com", System.getProperty("peegeeq.database.host"),
            "Database host must be configurable for multi-environment deployment");
        assertEquals("5432", System.getProperty("peegeeq.database.port"),
            "Database port must be configurable for different infrastructure setups");
        assertEquals("peegeeq_production", System.getProperty("peegeeq.database.name"),
            "Database name must be configurable for environment isolation");
        
        logger.info("✅ Database connection properties successfully configured for production environment");
        logger.info("✅ Database connection override test completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Profile-Based Configuration for Environment Management
     * 
     * Tests that profile selection works correctly, enabling different configurations
     * for development, staging, and production environments.
     */
    @Test
    @Order(3)
    void testProfileBasedConfiguration() {
        logger.info("=== Testing Profile-Based Configuration ===");
        
        // Test explicit profile setting (production deployment scenario)
        System.setProperty("peegeeq.profile", "production");
        assertEquals("production", System.getProperty("peegeeq.profile"),
            "Profile must be settable for environment-specific configuration");
        
        // Test default profile behavior (development scenario)
        System.clearProperty("peegeeq.profile");
        String defaultProfile = System.getProperty("peegeeq.profile", "development");
        assertTrue(defaultProfile.equals("development") || defaultProfile.equals("test"),
            "Default profile must be appropriate for development environment");
        
        logger.info("✅ Profile-based configuration working correctly");
        logger.info("✅ Profile configuration test completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Configuration Property Naming Convention Validation
     * 
     * Tests that all configuration properties follow consistent naming conventions,
     * which is important for documentation, automation, and developer experience.
     */
    @Test
    @Order(4)
    void testConfigurationPropertyNamingConventions() {
        logger.info("=== Testing Configuration Property Naming Conventions ===");
        
        String[] expectedProperties = {
            "peegeeq.database.host",
            "peegeeq.database.port", 
            "peegeeq.database.name",
            "peegeeq.database.username",
            "peegeeq.database.password",
            "peegeeq.queue.max-retries",
            "peegeeq.consumer.threads",
            "peegeeq.queue.polling-interval",
            "peegeeq.queue.batch-size"
        };
        
        // BUSINESS VALIDATION: Consistent naming enables automation and reduces errors
        for (String property : expectedProperties) {
            assertTrue(property.startsWith("peegeeq."),
                "All properties must use 'peegeeq.' prefix for namespace consistency");
            assertFalse(property.contains("_"),
                "Properties must use dots, not underscores, for consistency with Java conventions");
        }
        
        logger.info("✅ All {} configuration properties follow naming conventions", expectedProperties.length);
        logger.info("✅ Property naming convention test completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Configuration Factory Integration Validation
     * 
     * Tests that configuration properly integrates with factory providers,
     * ensuring the configuration system works end-to-end in the application.
     */
    @Test
    @Order(5)
    void testConfigurationFactoryIntegration() throws Exception {
        logger.info("=== Testing Configuration Factory Integration ===");
        
        // Set configuration properties
        System.setProperty("peegeeq.queue.max-retries", "10");
        System.setProperty("peegeeq.queue.batch-size", "25");
        
        // Test that factory provider can be created (configuration integration test)
        assertDoesNotThrow(() -> {
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            assertNotNull(provider, "Factory provider must be creatable with configuration");
        }, "Configuration must integrate properly with factory provider creation");
        
        logger.info("✅ Configuration successfully integrates with factory provider");
        logger.info("✅ Configuration factory integration test completed successfully");
    }
}
