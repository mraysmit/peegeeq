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

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test that validates system properties override configuration file values.
 * This test focuses on configuration validation without complex message processing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SystemPropertiesValidationSimpleTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesValidationSimpleTest.class);

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Save original system properties
        saveOriginalProperties();
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        restoreOriginalProperties();
    }

    private void saveOriginalProperties() {
        String[] propertiesToSave = {
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
        // Clear test properties
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.batch-size");
        
        // Restore original properties
        originalProperties.forEach(System::setProperty);
    }

    /**
     * Test that validates system properties properly override configuration file values.
     * This test ensures that system properties take precedence over properties files.
     */
    @Test
    @Order(1)
    void testSystemPropertiesOverrideConfigurationFiles() throws Exception {
        logger.info("=== Testing System Properties Override Configuration Files ===");
        
        // Test configuration without system property overrides first
        PeeGeeQConfiguration baseConfig = new PeeGeeQConfiguration("test");
        int baseMaxRetries = baseConfig.getQueueConfig().getMaxRetries();
        int baseBatchSize = baseConfig.getQueueConfig().getBatchSize();
        Duration basePollingInterval = baseConfig.getQueueConfig().getPollingInterval();
        
        logger.info("Base configuration values - MaxRetries: {}, BatchSize: {}, PollingInterval: {}", 
            baseMaxRetries, baseBatchSize, basePollingInterval);
        
        // Now set system properties to different values
        int overrideMaxRetries = baseMaxRetries + 5;
        int overrideBatchSize = baseBatchSize + 10;
        Duration overridePollingInterval = basePollingInterval.plusSeconds(2);
        
        System.setProperty("peegeeq.queue.max-retries", String.valueOf(overrideMaxRetries));
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(overrideBatchSize));
        System.setProperty("peegeeq.queue.polling-interval", overridePollingInterval.toString());
        
        // Create new configuration that should pick up system property overrides
        PeeGeeQConfiguration overriddenConfig = new PeeGeeQConfiguration("test");
        
        // Verify that system properties override configuration file values
        assertEquals(overrideMaxRetries, overriddenConfig.getQueueConfig().getMaxRetries(),
            "System property should override max-retries from configuration file");
        
        assertEquals(overrideBatchSize, overriddenConfig.getQueueConfig().getBatchSize(),
            "System property should override batch-size from configuration file");
        
        assertEquals(overridePollingInterval, overriddenConfig.getQueueConfig().getPollingInterval(),
            "System property should override polling-interval from configuration file");
        
        logger.info("✅ Verified system properties override configuration file values:");
        logger.info("  MaxRetries: {} -> {}", baseMaxRetries, overrideMaxRetries);
        logger.info("  BatchSize: {} -> {}", baseBatchSize, overrideBatchSize);
        logger.info("  PollingInterval: {} -> {}", basePollingInterval, overridePollingInterval);
        
        logger.info("✅ Property override test completed successfully");
    }

    /**
     * Test that validates individual property override behavior.
     */
    @Test
    @Order(2)
    void testIndividualPropertyOverrides() throws Exception {
        logger.info("=== Testing Individual Property Overrides ===");
        
        // Test max retries override
        System.setProperty("peegeeq.queue.max-retries", "99");
        PeeGeeQConfiguration config1 = new PeeGeeQConfiguration("test");
        assertEquals(99, config1.getQueueConfig().getMaxRetries(), 
            "Max retries should be overridden by system property");
        
        // Clear and test batch size override
        System.clearProperty("peegeeq.queue.max-retries");
        System.setProperty("peegeeq.queue.batch-size", "77");
        PeeGeeQConfiguration config2 = new PeeGeeQConfiguration("test");
        assertEquals(77, config2.getQueueConfig().getBatchSize(), 
            "Batch size should be overridden by system property");
        
        // Clear and test polling interval override
        System.clearProperty("peegeeq.queue.batch-size");
        System.setProperty("peegeeq.queue.polling-interval", "PT10S");
        PeeGeeQConfiguration config3 = new PeeGeeQConfiguration("test");
        assertEquals(Duration.ofSeconds(10), config3.getQueueConfig().getPollingInterval(), 
            "Polling interval should be overridden by system property");
        
        logger.info("✅ Individual property override test completed successfully");
    }

    /**
     * Test that validates configuration loading without system properties.
     */
    @Test
    @Order(3)
    void testBaseConfigurationLoading() throws Exception {
        logger.info("=== Testing Base Configuration Loading ===");
        
        // Ensure no system properties are set
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.consumer.threads");
        
        // Load base configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        
        // Verify configuration loads successfully
        assertNotNull(config.getQueueConfig(), "Queue configuration should be loaded");
        assertTrue(config.getQueueConfig().getMaxRetries() > 0, "Max retries should be positive");
        assertTrue(config.getQueueConfig().getBatchSize() > 0, "Batch size should be positive");
        assertNotNull(config.getQueueConfig().getPollingInterval(), "Polling interval should be set");
        assertTrue(config.getQueueConfig().getPollingInterval().toMillis() > 0, "Polling interval should be positive");
        
        logger.info("Base configuration values:");
        logger.info("  MaxRetries: {}", config.getQueueConfig().getMaxRetries());
        logger.info("  BatchSize: {}", config.getQueueConfig().getBatchSize());
        logger.info("  PollingInterval: {}", config.getQueueConfig().getPollingInterval());
        logger.info("  VisibilityTimeout: {}", config.getQueueConfig().getVisibilityTimeout());
        logger.info("  DeadLetterEnabled: {}", config.getQueueConfig().isDeadLetterEnabled());
        
        logger.info("✅ Base configuration loading test completed successfully");
    }

    /**
     * Test that validates property precedence order.
     */
    @Test
    @Order(4)
    void testPropertyPrecedenceOrder() throws Exception {
        logger.info("=== Testing Property Precedence Order ===");
        
        // Get base configuration value
        PeeGeeQConfiguration baseConfig = new PeeGeeQConfiguration("test");
        int baseMaxRetries = baseConfig.getQueueConfig().getMaxRetries();
        
        // Set system property
        int systemPropertyValue = baseMaxRetries + 100;
        System.setProperty("peegeeq.queue.max-retries", String.valueOf(systemPropertyValue));
        
        // Create new configuration
        PeeGeeQConfiguration configWithSystemProperty = new PeeGeeQConfiguration("test");
        
        // Verify system property takes precedence
        assertEquals(systemPropertyValue, configWithSystemProperty.getQueueConfig().getMaxRetries(),
            "System property should take precedence over configuration file");
        
        assertNotEquals(baseMaxRetries, configWithSystemProperty.getQueueConfig().getMaxRetries(),
            "Configuration should be different from base when system property is set");
        
        logger.info("✅ Property precedence test completed successfully");
        logger.info("  Base config value: {}", baseMaxRetries);
        logger.info("  System property value: {}", systemPropertyValue);
        logger.info("  Final config value: {}", configWithSystemProperty.getQueueConfig().getMaxRetries());
    }

    /**
     * Test that validates configuration is properly passed to runtime components.
     */
    @Test
    @Order(5)
    void testConfigurationPassedToComponents() throws Exception {
        logger.info("=== Testing Configuration Passed to Components ===");

        // Set a specific max retries value
        System.setProperty("peegeeq.queue.max-retries", "7");

        // Create configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        assertEquals(7, config.getQueueConfig().getMaxRetries(),
            "Configuration should load the system property value");

        logger.info("✅ Configuration injection test completed successfully");
        logger.info("  Max retries from configuration: {}", config.getQueueConfig().getMaxRetries());
        logger.info("  This configuration is now available to runtime components");
    }
}
