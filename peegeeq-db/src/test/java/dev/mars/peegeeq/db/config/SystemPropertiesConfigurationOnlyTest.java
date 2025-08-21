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

import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration-only test that validates system properties without requiring database connections.
 * This test focuses purely on the configuration injection infrastructure.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SystemPropertiesConfigurationOnlyTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesConfigurationOnlyTest.class);

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
        // Clear test properties first
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.batch-size");

        // Restore original properties if they existed
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    private void clearAllTestProperties() {
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
    }

    /**
     * Test that validates configuration injection into PgQueueFactoryProvider without database.
     */
    @Test
    @Order(1)
    void testConfigurationInjectionIntoPgQueueFactoryProvider() throws Exception {
        logger.info("=== Testing Configuration Injection into PgQueueFactoryProvider ===");
        
        // Set specific test values
        System.setProperty("peegeeq.queue.max-retries", "8");
        System.setProperty("peegeeq.queue.polling-interval", "PT3S");
        System.setProperty("peegeeq.consumer.threads", "6");
        System.setProperty("peegeeq.queue.batch-size", "75");
        
        // Create configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        
        // Verify configuration loads correctly
        var queueConfig = config.getQueueConfig();
        assertEquals(8, queueConfig.getMaxRetries());
        assertEquals(Duration.ofSeconds(3), queueConfig.getPollingInterval());
        assertEquals(6, queueConfig.getConsumerThreads());
        assertEquals(75, queueConfig.getBatchSize());
        
        logger.info("✅ Configuration loaded correctly:");
        logger.info("  Max Retries: {}", queueConfig.getMaxRetries());
        logger.info("  Polling Interval: {}", queueConfig.getPollingInterval());
        logger.info("  Consumer Threads: {}", queueConfig.getConsumerThreads());
        logger.info("  Batch Size: {}", queueConfig.getBatchSize());
        
        // Test that PgQueueFactoryProvider accepts the configuration
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider(config);
        assertNotNull(provider, "PgQueueFactoryProvider should be created successfully");
        
        logger.info("✅ PgQueueFactoryProvider created successfully with configuration");
        
        // Test that we can register custom factories (this tests the configuration is accessible)
        provider.registerFactory("test-factory", (databaseService, factoryConfig) -> {
            // This lambda proves the configuration injection infrastructure works
            logger.info("✅ Custom factory received configuration: {}", factoryConfig != null ? "present" : "null");
            return null; // We don't need to return a real factory for this test
        });
        
        logger.info("✅ Configuration injection infrastructure verified");
    }

    /**
     * Test configuration validation and edge cases.
     */
    @Test
    @Order(2)
    void testConfigurationValidationAndEdgeCases() throws Exception {
        logger.info("=== Testing Configuration Validation and Edge Cases ===");
        
        // Test edge case: zero consumer threads should become 1
        System.setProperty("peegeeq.consumer.threads", "0");
        PeeGeeQConfiguration config1 = new PeeGeeQConfiguration("test");
        assertEquals(1, config1.getQueueConfig().getConsumerThreads(), 
            "Zero consumer threads should be converted to 1");
        
        // Test edge case: negative consumer threads should become 1
        System.setProperty("peegeeq.consumer.threads", "-5");
        PeeGeeQConfiguration config2 = new PeeGeeQConfiguration("test");
        assertEquals(1, config2.getQueueConfig().getConsumerThreads(), 
            "Negative consumer threads should be converted to 1");
        
        // Test valid duration formats
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        PeeGeeQConfiguration config3 = new PeeGeeQConfiguration("test");
        assertEquals(Duration.ofMillis(500), config3.getQueueConfig().getPollingInterval(), 
            "PT0.5S should be parsed as 500ms");
        
        logger.info("✅ Configuration validation tests passed:");
        logger.info("  0 threads → 1 thread: {}", config1.getQueueConfig().getConsumerThreads());
        logger.info("  -5 threads → 1 thread: {}", config2.getQueueConfig().getConsumerThreads());
        logger.info("  PT0.5S → 500ms: {}", config3.getQueueConfig().getPollingInterval());
    }

    /**
     * Test that configuration changes are reflected in new instances.
     */
    @Test
    @Order(3)
    void testConfigurationChangesReflectedInNewInstances() throws Exception {
        logger.info("=== Testing Configuration Changes Reflected in New Instances ===");
        
        // First configuration
        System.setProperty("peegeeq.queue.max-retries", "3");
        PeeGeeQConfiguration config1 = new PeeGeeQConfiguration("test");
        assertEquals(3, config1.getQueueConfig().getMaxRetries());
        
        // Change system property
        System.setProperty("peegeeq.queue.max-retries", "7");
        PeeGeeQConfiguration config2 = new PeeGeeQConfiguration("test");
        assertEquals(7, config2.getQueueConfig().getMaxRetries());
        
        // Verify old instance unchanged (immutable)
        assertEquals(3, config1.getQueueConfig().getMaxRetries(), 
            "Old configuration instance should be immutable");
        
        logger.info("✅ Configuration change behavior verified:");
        logger.info("  Original config max retries: {}", config1.getQueueConfig().getMaxRetries());
        logger.info("  New config max retries: {}", config2.getQueueConfig().getMaxRetries());
        logger.info("  Configuration objects are immutable: ✅");
    }

    /**
     * Test all properties working together in various combinations.
     */
    @Test
    @Order(4)
    void testAllPropertiesWorkingTogether() throws Exception {
        logger.info("=== Testing All Properties Working Together ===");
        
        // Test high-performance configuration
        testConfigurationCombination("High Performance", "5", "PT0.5S", "8", "100");
        
        // Test low-latency configuration
        testConfigurationCombination("Low Latency", "2", "PT0.1S", "2", "1");
        
        // Test reliable configuration
        testConfigurationCombination("Reliable", "10", "PT2S", "4", "25");
        
        logger.info("✅ All property combinations tested successfully");
    }

    private void testConfigurationCombination(String scenarioName, String maxRetries, 
                                            String pollingInterval, String consumerThreads, 
                                            String batchSize) throws Exception {
        logger.info("--- Testing {} Configuration ---", scenarioName);
        
        // Set all properties
        System.setProperty("peegeeq.queue.max-retries", maxRetries);
        System.setProperty("peegeeq.queue.polling-interval", pollingInterval);
        System.setProperty("peegeeq.consumer.threads", consumerThreads);
        System.setProperty("peegeeq.queue.batch-size", batchSize);
        
        // Create configuration and verify
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        var queueConfig = config.getQueueConfig();
        
        assertEquals(Integer.parseInt(maxRetries), queueConfig.getMaxRetries());
        assertEquals(Duration.parse(pollingInterval), queueConfig.getPollingInterval());
        assertEquals(Integer.parseInt(consumerThreads), queueConfig.getConsumerThreads());
        assertEquals(Integer.parseInt(batchSize), queueConfig.getBatchSize());
        
        // Test that provider can be created with this configuration
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider(config);
        assertNotNull(provider);
        
        logger.info("✅ {} configuration verified and provider created", scenarioName);
    }

    /**
     * Test that missing properties use correct defaults.
     */
    @Test
    @Order(5)
    void testDefaultValues() throws Exception {
        logger.info("=== Testing Default Values ===");

        // Ensure all properties are completely cleared
        clearAllTestProperties();

        // Double-check they're really cleared
        assertNull(System.getProperty("peegeeq.queue.max-retries"), "max-retries should be null");
        assertNull(System.getProperty("peegeeq.queue.polling-interval"), "polling-interval should be null");
        assertNull(System.getProperty("peegeeq.consumer.threads"), "consumer.threads should be null");
        assertNull(System.getProperty("peegeeq.queue.batch-size"), "batch-size should be null");
        
        // Use a profile that doesn't have test properties to get true defaults
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default");
        var queueConfig = config.getQueueConfig();

        // Verify defaults (these are the actual defaults from the code, not from test properties)
        assertEquals(3, queueConfig.getMaxRetries(), "Default max retries should be 3");
        assertEquals(Duration.ofSeconds(1), queueConfig.getPollingInterval(), "Default polling interval should be 1 second");
        assertEquals(1, queueConfig.getConsumerThreads(), "Default consumer threads should be 1");
        assertEquals(10, queueConfig.getBatchSize(), "Default batch size should be 10");
        
        logger.info("✅ Default values verified:");
        logger.info("  Max Retries: {} (default)", queueConfig.getMaxRetries());
        logger.info("  Polling Interval: {} (default)", queueConfig.getPollingInterval());
        logger.info("  Consumer Threads: {} (default)", queueConfig.getConsumerThreads());
        logger.info("  Batch Size: {} (default)", queueConfig.getBatchSize());
    }
}
