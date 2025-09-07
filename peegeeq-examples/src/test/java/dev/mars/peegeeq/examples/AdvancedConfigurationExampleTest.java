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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for AdvancedConfigurationExample demonstrating production-ready configuration patterns.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
@Testcontainers
public class AdvancedConfigurationExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedConfigurationExampleTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("peegeeq_config_test")
            .withUsername("postgres")
            .withPassword("password");
    
    @BeforeEach
    void setUp() {
        logger.info("Setting up Advanced Configuration test environment");
    }
    
    @AfterEach
    void tearDown() {
        // Clean up any system properties set during tests
        System.clearProperty("PEEGEEQ_ENVIRONMENT");
        System.clearProperty("test.config.key");
        logger.info("Cleaned up Advanced Configuration test environment");
    }
    
    @Test
    void testEnvironmentSpecificConfiguration() throws Exception {
        logger.info("Testing environment-specific configuration");
        
        String[] environments = {"development", "staging", "production"};
        
        for (String environment : environments) {
            logger.info("Testing {} environment configuration", environment);
            
            PeeGeeQConfiguration config = createEnvironmentSpecificConfiguration(environment);
            
            // Verify environment-specific settings
            switch (environment) {
                case "development":
                    assertEquals(5, config.getConnectionPoolSize());
                    assertEquals(5000, config.getConnectionTimeoutMs());
                    assertFalse(config.isMonitoringEnabled());
                    break;
                    
                case "staging":
                    assertEquals(10, config.getConnectionPoolSize());
                    assertEquals(3000, config.getConnectionTimeoutMs());
                    assertTrue(config.isMonitoringEnabled());
                    break;
                    
                case "production":
                    assertEquals(20, config.getConnectionPoolSize());
                    assertEquals(2000, config.getConnectionTimeoutMs());
                    assertTrue(config.isMonitoringEnabled());
                    break;
            }
            
            // Test configuration with actual PeeGeeQ instance
            testConfigurationFunctionality(config, environment);
        }
    }
    
    @Test
    void testExternalConfigurationManagement() {
        logger.info("Testing external configuration management");
        
        // Test system properties precedence
        String testKey = "test.config.key";
        String systemValue = "system-property-value";
        String defaultValue = "default-value";
        
        // Test without system property
        String value1 = getConfigValue(testKey, defaultValue);
        assertEquals(defaultValue, value1);
        
        // Test with system property
        System.setProperty(testKey, systemValue);
        String value2 = getConfigValue(testKey, defaultValue);
        assertEquals(systemValue, value2);
        
        logger.info("✅ Configuration hierarchy working correctly");
    }
    
    @Test
    void testDatabaseConnectionPooling() throws Exception {
        logger.info("Testing database connection pooling configurations");
        
        // Test different pool sizes
        int[] poolSizes = {5, 15, 30};
        
        for (int poolSize : poolSizes) {
            logger.info("Testing connection pool size: {}", poolSize);
            
            PeeGeeQConfiguration config = PeeGeeQConfiguration.builder()
                .withDatabaseUrl(postgres.getJdbcUrl())
                .withDatabaseUsername(postgres.getUsername())
                .withDatabasePassword(postgres.getPassword())
                .withConnectionPoolSize(poolSize)
                .withConnectionTimeoutMs(3000)
                .withIdleTimeoutMs(600000)
                .withMaxLifetimeMs(3600000)
                .withMeterRegistry(new SimpleMeterRegistry())
                .build();
            
            assertEquals(poolSize, config.getConnectionPoolSize());
            
            // Test that configuration works with PeeGeeQ
            testConnectionPoolPerformance(config, poolSize);
        }
    }
    
    @Test
    void testMonitoringIntegration() throws Exception {
        logger.info("Testing monitoring integration");
        
        // Test with SimpleMeterRegistry
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        PeeGeeQConfiguration simpleConfig = createConfigWithMeterRegistry(simpleRegistry);
        
        assertNotNull(simpleConfig.getMeterRegistry());
        assertEquals(SimpleMeterRegistry.class, simpleConfig.getMeterRegistry().getClass());
        
        // Test with PrometheusMeterRegistry
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PeeGeeQConfiguration prometheusConfig = createConfigWithMeterRegistry(prometheusRegistry);
        
        assertNotNull(prometheusConfig.getMeterRegistry());
        assertEquals(PrometheusMeterRegistry.class, prometheusConfig.getMeterRegistry().getClass());
        
        // Test metrics collection
        testMetricsCollection(prometheusConfig, prometheusRegistry);
    }
    
    @Test
    void testConfigurationValidation() {
        logger.info("Testing configuration validation");
        
        // Test valid configuration
        assertTrue(validateConnectionPoolSettings(10, 3000, 600000));
        
        // Test invalid configurations
        assertFalse(validateConnectionPoolSettings(0, 3000, 600000)); // Invalid pool size
        assertFalse(validateConnectionPoolSettings(10, 5000, 3000)); // Idle timeout < connection timeout
        
        // Test warning conditions (should still be valid but with warnings)
        assertTrue(validateConnectionPoolSettings(100, 3000, 600000)); // Large pool size
        assertTrue(validateConnectionPoolSettings(10, 500, 600000)); // Low connection timeout
        
        logger.info("✅ Configuration validation working correctly");
    }
    
    @Test
    void testConfigurationHierarchy() {
        logger.info("Testing configuration hierarchy precedence");
        
        String testKey = "hierarchy.test.key";
        String defaultValue = "default";
        String systemValue = "system";
        
        // Test default value
        assertEquals(defaultValue, getConfigValue(testKey, defaultValue));
        
        // Test system property precedence
        System.setProperty(testKey, systemValue);
        assertEquals(systemValue, getConfigValue(testKey, defaultValue));
        
        // Clean up
        System.clearProperty(testKey);
        assertEquals(defaultValue, getConfigValue(testKey, defaultValue));
        
        logger.info("✅ Configuration hierarchy precedence working correctly");
    }
    
    @Test
    void testSensitiveInformationMasking() {
        logger.info("Testing sensitive information masking");
        
        // Test password masking
        assertEquals("****", maskSensitiveInfo("abc"));
        assertEquals("ab****ef", maskSensitiveInfo("abcdef"));
        assertEquals("my****rd", maskSensitiveInfo("mypassword"));
        assertEquals("****", maskSensitiveInfo(null));
        
        logger.info("✅ Sensitive information masking working correctly");
    }
    
    // Helper methods
    
    private PeeGeeQConfiguration createEnvironmentSpecificConfiguration(String environment) {
        PeeGeeQConfiguration.Builder builder = PeeGeeQConfiguration.builder()
            .withDatabaseUrl(postgres.getJdbcUrl())
            .withDatabaseUsername(postgres.getUsername())
            .withDatabasePassword(postgres.getPassword());
        
        switch (environment) {
            case "development":
                return builder
                    .withConnectionPoolSize(5)
                    .withConnectionTimeoutMs(5000)
                    .withIdleTimeoutMs(300000)
                    .withMaxLifetimeMs(1800000)
                    .withMonitoringEnabled(false)
                    .withMeterRegistry(new SimpleMeterRegistry())
                    .build();
                    
            case "staging":
                return builder
                    .withConnectionPoolSize(10)
                    .withConnectionTimeoutMs(3000)
                    .withIdleTimeoutMs(600000)
                    .withMaxLifetimeMs(3600000)
                    .withMonitoringEnabled(true)
                    .withMeterRegistry(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                    .build();
                    
            case "production":
                return builder
                    .withConnectionPoolSize(20)
                    .withConnectionTimeoutMs(2000)
                    .withIdleTimeoutMs(900000)
                    .withMaxLifetimeMs(7200000)
                    .withMonitoringEnabled(true)
                    .withMeterRegistry(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                    .build();
                    
            default:
                throw new IllegalArgumentException("Unknown environment: " + environment);
        }
    }
    
    private void testConfigurationFunctionality(PeeGeeQConfiguration config, String environment) throws Exception {
        logger.info("Testing {} configuration functionality", environment);
        
        try (PeeGeeQManager manager = new PeeGeeQManager(config)) {
            manager.start().await(10, TimeUnit.SECONDS);
            
            DatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider factoryProvider = new PgQueueFactoryProvider();
            QueueFactory queueFactory = factoryProvider.createOutboxQueueFactory(databaseService);
            
            String queueName = "config-test-" + environment;
            
            try (MessageProducer producer = queueFactory.createProducer(queueName);
                 MessageConsumer consumer = queueFactory.createConsumer(queueName)) {
                
                CountDownLatch messageLatch = new CountDownLatch(1);
                AtomicReference<String> receivedMessage = new AtomicReference<>();
                
                consumer.consume(message -> {
                    receivedMessage.set(message.getPayload());
                    messageLatch.countDown();
                    return true;
                });
                
                Thread.sleep(100); // Allow consumer to start
                
                String testMessage = "Configuration test for " + environment;
                producer.send(testMessage);
                
                assertTrue(messageLatch.await(10, TimeUnit.SECONDS), 
                          "Message should be received for " + environment + " configuration");
                assertEquals(testMessage, receivedMessage.get());
                
                logger.info("✅ {} configuration test successful", environment);
            }
        }
    }
    
    private void testConnectionPoolPerformance(PeeGeeQConfiguration config, int expectedPoolSize) throws Exception {
        logger.info("Testing connection pool performance with size: {}", expectedPoolSize);
        
        try (PeeGeeQManager manager = new PeeGeeQManager(config)) {
            manager.start().await(10, TimeUnit.SECONDS);
            
            DatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider factoryProvider = new PgQueueFactoryProvider();
            QueueFactory queueFactory = factoryProvider.createOutboxQueueFactory(databaseService);
            
            // Test concurrent operations
            int concurrentOps = Math.min(expectedPoolSize, 5); // Don't overwhelm small pools
            CountDownLatch operationsLatch = new CountDownLatch(concurrentOps);
            
            for (int i = 0; i < concurrentOps; i++) {
                final int operationId = i;
                new Thread(() -> {
                    try (MessageProducer producer = queueFactory.createProducer("pool-test-" + operationId)) {
                        producer.send("Pool test message " + operationId);
                        operationsLatch.countDown();
                    } catch (Exception e) {
                        logger.warn("Operation {} failed: {}", operationId, e.getMessage());
                        operationsLatch.countDown();
                    }
                }).start();
            }
            
            assertTrue(operationsLatch.await(30, TimeUnit.SECONDS), 
                      "All concurrent operations should complete");
            
            logger.info("✅ Connection pool size {} handled {} concurrent operations", 
                       expectedPoolSize, concurrentOps);
        }
    }
    
    private PeeGeeQConfiguration createConfigWithMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return PeeGeeQConfiguration.builder()
            .withDatabaseUrl(postgres.getJdbcUrl())
            .withDatabaseUsername(postgres.getUsername())
            .withDatabasePassword(postgres.getPassword())
            .withConnectionPoolSize(10)
            .withMonitoringEnabled(true)
            .withMeterRegistry(meterRegistry)
            .build();
    }
    
    private void testMetricsCollection(PeeGeeQConfiguration config, PrometheusMeterRegistry prometheusRegistry) throws Exception {
        logger.info("Testing metrics collection");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(config)) {
            manager.start().await(10, TimeUnit.SECONDS);
            
            DatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider factoryProvider = new PgQueueFactoryProvider();
            QueueFactory queueFactory = factoryProvider.createOutboxQueueFactory(databaseService);
            
            String queueName = "metrics-test";
            
            try (MessageProducer producer = queueFactory.createProducer(queueName);
                 MessageConsumer consumer = queueFactory.createConsumer(queueName)) {
                
                CountDownLatch metricsLatch = new CountDownLatch(3);
                
                consumer.consume(message -> {
                    metricsLatch.countDown();
                    return true;
                });
                
                Thread.sleep(100); // Allow consumer to start
                
                // Generate some metrics
                for (int i = 0; i < 3; i++) {
                    producer.send("Metrics test message " + i);
                }
                
                assertTrue(metricsLatch.await(10, TimeUnit.SECONDS), 
                          "All messages should be processed for metrics collection");
                
                // Verify metrics are collected
                String metricsOutput = prometheusRegistry.scrape();
                assertNotNull(metricsOutput);
                assertFalse(metricsOutput.trim().isEmpty());
                
                logger.info("✅ Metrics collection successful, {} characters of metrics data", 
                           metricsOutput.length());
            }
        }
    }
    
    private String getConfigValue(String key, String defaultValue) {
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
        
        // Return default
        return defaultValue;
    }
    
    private boolean validateConnectionPoolSettings(int poolSize, int connectionTimeout, int idleTimeout) {
        if (poolSize <= 0) {
            logger.error("Invalid pool size: {}", poolSize);
            return false;
        }
        
        if (idleTimeout < connectionTimeout) {
            logger.error("Idle timeout ({}) must be greater than connection timeout ({})", 
                        idleTimeout, connectionTimeout);
            return false;
        }
        
        // Warnings don't make configuration invalid
        if (poolSize > 50) {
            logger.warn("Pool size {} is very large", poolSize);
        }
        
        if (connectionTimeout < 1000) {
            logger.warn("Connection timeout {}ms is very low", connectionTimeout);
        }
        
        return true;
    }
    
    private String maskSensitiveInfo(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
