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

import dev.mars.peegeeq.db.PeeGeeQManager;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates all system properties working together in a real PeeGeeQManager.
 * This test demonstrates that the configuration injection infrastructure works end-to-end.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SystemPropertiesIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesIntegrationTest.class);

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
     * Test that validates all system properties are properly injected into a real PeeGeeQManager.
     */
    @Test
    @Order(1)
    void testSystemPropertiesEndToEndIntegration() throws Exception {
        logger.info("=== Testing End-to-End System Properties Integration ===");
        
        // Set all system properties to specific test values
        System.setProperty("peegeeq.queue.max-retries", "7");
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");
        System.setProperty("peegeeq.consumer.threads", "4");
        System.setProperty("peegeeq.queue.batch-size", "50");
        
        // Create PeeGeeQManager and verify configuration is injected
        try (PeeGeeQManager manager = new PeeGeeQManager("test")) {
            // Verify configuration is loaded correctly
            var config = manager.getConfiguration().getQueueConfig();
            
            assertEquals(7, config.getMaxRetries(), 
                "Max retries should be loaded from system property");
            assertEquals(Duration.ofSeconds(2), config.getPollingInterval(), 
                "Polling interval should be loaded from system property");
            assertEquals(4, config.getConsumerThreads(), 
                "Consumer threads should be loaded from system property");
            assertEquals(50, config.getBatchSize(), 
                "Batch size should be loaded from system property");
            
            logger.info("✅ Configuration loaded successfully:");
            logger.info("  Max Retries: {}", config.getMaxRetries());
            logger.info("  Polling Interval: {}", config.getPollingInterval());
            logger.info("  Consumer Threads: {}", config.getConsumerThreads());
            logger.info("  Batch Size: {}", config.getBatchSize());
            
            // Initialize the manager to verify configuration injection works
            manager.start();
            
            // Verify the queue factory provider has the configuration
            assertNotNull(manager.getQueueFactoryProvider(), 
                "Queue factory provider should be available");
            
            logger.info("✅ PeeGeeQManager started successfully with custom configuration");
            
            // Test that we can create a queue factory (this tests the configuration injection)
            var queueFactory = manager.getQueueFactoryProvider()
                .createFactory("outbox", manager.getDatabaseService());
            
            assertNotNull(queueFactory, "Queue factory should be created successfully");
            
            logger.info("✅ Queue factory created successfully with injected configuration");
            
            // Cleanup
            queueFactory.close();
        }
        
        logger.info("✅ End-to-end integration test completed successfully");
    }

    /**
     * Test different configuration scenarios to ensure flexibility.
     */
    @Test
    @Order(2)
    void testDifferentConfigurationScenarios() throws Exception {
        logger.info("=== Testing Different Configuration Scenarios ===");
        
        // Test high-performance configuration
        testConfigurationScenario("High Performance", "5", "PT0.5S", "8", "100");
        
        // Test low-latency configuration
        testConfigurationScenario("Low Latency", "3", "PT0.1S", "2", "1");
        
        // Test reliable configuration
        testConfigurationScenario("Reliable", "10", "PT3S", "4", "25");
        
        logger.info("✅ All configuration scenarios tested successfully");
    }

    private void testConfigurationScenario(String scenarioName, String maxRetries, 
                                         String pollingInterval, String consumerThreads, 
                                         String batchSize) throws Exception {
        logger.info("--- Testing {} Configuration ---", scenarioName);
        
        // Set scenario-specific properties
        System.setProperty("peegeeq.queue.max-retries", maxRetries);
        System.setProperty("peegeeq.queue.polling-interval", pollingInterval);
        System.setProperty("peegeeq.consumer.threads", consumerThreads);
        System.setProperty("peegeeq.queue.batch-size", batchSize);
        
        try (PeeGeeQManager manager = new PeeGeeQManager("test")) {
            var config = manager.getConfiguration().getQueueConfig();
            
            // Verify all properties are set correctly
            assertEquals(Integer.parseInt(maxRetries), config.getMaxRetries());
            assertEquals(Duration.parse(pollingInterval), config.getPollingInterval());
            assertEquals(Integer.parseInt(consumerThreads), config.getConsumerThreads());
            assertEquals(Integer.parseInt(batchSize), config.getBatchSize());
            
            logger.info("✅ {} configuration verified: retries={}, interval={}, threads={}, batch={}", 
                scenarioName, config.getMaxRetries(), config.getPollingInterval(), 
                config.getConsumerThreads(), config.getBatchSize());
        }
    }

    /**
     * Test that default values are used when system properties are not set.
     */
    @Test
    @Order(3)
    void testDefaultConfigurationValues() throws Exception {
        logger.info("=== Testing Default Configuration Values ===");
        
        // Ensure no system properties are set
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
        
        try (PeeGeeQManager manager = new PeeGeeQManager("test")) {
            var config = manager.getConfiguration().getQueueConfig();
            
            // Verify default values
            assertEquals(3, config.getMaxRetries(), "Default max retries should be 3");
            assertEquals(Duration.ofSeconds(1), config.getPollingInterval(), "Default polling interval should be 1 second");
            assertEquals(1, config.getConsumerThreads(), "Default consumer threads should be 1");
            assertEquals(10, config.getBatchSize(), "Default batch size should be 10");
            
            logger.info("✅ Default configuration verified:");
            logger.info("  Max Retries: {} (default)", config.getMaxRetries());
            logger.info("  Polling Interval: {} (default)", config.getPollingInterval());
            logger.info("  Consumer Threads: {} (default)", config.getConsumerThreads());
            logger.info("  Batch Size: {} (default)", config.getBatchSize());
        }
        
        logger.info("✅ Default configuration test completed successfully");
    }

    /**
     * Test that invalid property values are handled gracefully.
     */
    @Test
    @Order(4)
    void testInvalidPropertyHandling() throws Exception {
        logger.info("=== Testing Invalid Property Handling ===");
        
        // Test with some invalid values
        System.setProperty("peegeeq.queue.max-retries", "5"); // Valid
        System.setProperty("peegeeq.queue.polling-interval", "PT1S"); // Valid
        System.setProperty("peegeeq.consumer.threads", "0"); // Invalid - should become 1
        System.setProperty("peegeeq.queue.batch-size", "25"); // Valid
        
        try (PeeGeeQManager manager = new PeeGeeQManager("test")) {
            var config = manager.getConfiguration().getQueueConfig();
            
            assertEquals(5, config.getMaxRetries());
            assertEquals(Duration.ofSeconds(1), config.getPollingInterval());
            assertEquals(1, config.getConsumerThreads(), "Invalid consumer threads (0) should be converted to 1");
            assertEquals(25, config.getBatchSize());
            
            logger.info("✅ Invalid property handling verified - consumer threads 0 converted to 1");
        }
        
        logger.info("✅ Invalid property handling test completed successfully");
    }
}
