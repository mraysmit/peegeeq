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

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.examples.springboot.config.PeeGeeQConfig;
import dev.mars.peegeeq.examples.springboot.config.PeeGeeQProperties;
import dev.mars.peegeeq.examples.springboot.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot.events.PaymentEvent;
import dev.mars.peegeeq.outbox.OutboxProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PeeGeeQ Spring Boot Configuration.
 * 
 * This test verifies that all PeeGeeQ components are properly configured
 * and integrated as Spring beans.
 * 
 * Key Features Tested:
 * - PeeGeeQ Manager bean creation and lifecycle
 * - Outbox factory configuration
 * - Producer bean creation and injection
 * - Configuration properties binding
 * - System properties configuration
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-09
 * @version 1.0
 */
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=DEBUG"
    }
)
@Testcontainers
class PeeGeeQConfigTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQConfigTest.class);
    
    @Autowired
    private PeeGeeQManager peeGeeQManager;
    
    @Autowired
    private QueueFactory outboxFactory;
    
    @Autowired
    private OutboxProducer<OrderEvent> orderEventProducer;
    
    @Autowired
    private OutboxProducer<PaymentEvent> paymentEventProducer;
    
    @Autowired
    private PeeGeeQProperties peeGeeQProperties;
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .withSharedMemorySize(256 * 1024 * 1024L);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for PeeGeeQConfig test");
        
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.profile", () -> "test");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }
    
    /**
     * Test that PeeGeeQ Manager bean is properly created and configured.
     */
    @Test
    void testPeeGeeQManagerBean() {
        logger.info("=== Testing PeeGeeQ Manager Bean ===");
        
        assertNotNull(peeGeeQManager, "PeeGeeQ Manager should be injected");
        assertTrue(peeGeeQManager.isStarted(), "PeeGeeQ Manager should be started");
        
        logger.info("✅ PeeGeeQ Manager bean created and started successfully");
        logger.info("✅ PeeGeeQ Manager test passed");
    }
    
    /**
     * Test that Outbox Factory bean is properly created and configured.
     */
    @Test
    void testOutboxFactoryBean() {
        logger.info("=== Testing Outbox Factory Bean ===");
        
        assertNotNull(outboxFactory, "Outbox Factory should be injected");
        
        logger.info("✅ Outbox Factory bean created successfully");
        logger.info("✅ Outbox Factory test passed");
    }
    
    /**
     * Test that Order Event Producer bean is properly created and configured.
     */
    @Test
    void testOrderEventProducerBean() {
        logger.info("=== Testing Order Event Producer Bean ===");
        
        assertNotNull(orderEventProducer, "Order Event Producer should be injected");
        
        logger.info("✅ Order Event Producer bean created successfully");
        logger.info("✅ Order Event Producer test passed");
    }
    
    /**
     * Test that Payment Event Producer bean is properly created and configured.
     */
    @Test
    void testPaymentEventProducerBean() {
        logger.info("=== Testing Payment Event Producer Bean ===");
        
        assertNotNull(paymentEventProducer, "Payment Event Producer should be injected");
        
        logger.info("✅ Payment Event Producer bean created successfully");
        logger.info("✅ Payment Event Producer test passed");
    }
    
    /**
     * Test that PeeGeeQ Properties are properly bound and configured.
     */
    @Test
    void testPeeGeeQPropertiesBinding() {
        logger.info("=== Testing PeeGeeQ Properties Binding ===");
        
        assertNotNull(peeGeeQProperties, "PeeGeeQ Properties should be injected");
        assertNotNull(peeGeeQProperties.getDatabase(), "Database properties should be configured");
        assertNotNull(peeGeeQProperties.getPool(), "Pool properties should be configured");
        assertNotNull(peeGeeQProperties.getQueue(), "Queue properties should be configured");
        
        // Verify database properties are set correctly
        assertEquals(postgres.getHost(), peeGeeQProperties.getDatabase().getHost());
        assertEquals(postgres.getFirstMappedPort(), peeGeeQProperties.getDatabase().getPort());
        assertEquals(postgres.getDatabaseName(), peeGeeQProperties.getDatabase().getName());
        assertEquals(postgres.getUsername(), peeGeeQProperties.getDatabase().getUsername());
        assertEquals(postgres.getPassword(), peeGeeQProperties.getDatabase().getPassword());
        
        logger.info("✅ PeeGeeQ Properties bound correctly");
        logger.info("✅ Properties binding test passed");
    }
    
    /**
     * Test that system properties are configured correctly from Spring properties.
     */
    @Test
    void testSystemPropertiesConfiguration() {
        logger.info("=== Testing System Properties Configuration ===");
        
        // Verify that system properties have been set correctly
        assertEquals(postgres.getHost(), System.getProperty("peegeeq.database.host"));
        assertEquals(postgres.getFirstMappedPort().toString(), System.getProperty("peegeeq.database.port"));
        assertEquals(postgres.getDatabaseName(), System.getProperty("peegeeq.database.name"));
        assertEquals(postgres.getUsername(), System.getProperty("peegeeq.database.username"));
        assertEquals(postgres.getPassword(), System.getProperty("peegeeq.database.password"));
        assertEquals("public", System.getProperty("peegeeq.database.schema"));
        
        logger.info("✅ System properties configured correctly");
        logger.info("✅ System properties test passed");
    }
    
    /**
     * Test that all beans work together in integration.
     */
    @Test
    void testBeansIntegration() {
        logger.info("=== Testing Beans Integration ===");
        
        // Verify that all beans are properly wired and can work together
        assertNotNull(peeGeeQManager);
        assertNotNull(outboxFactory);
        assertNotNull(orderEventProducer);
        assertNotNull(paymentEventProducer);
        assertNotNull(peeGeeQProperties);
        
        // Verify that the manager is running
        assertTrue(peeGeeQManager.isStarted());
        
        logger.info("✅ All beans properly integrated and working together");
        logger.info("✅ Beans integration test passed");
    }
    
    /**
     * Test configuration profile handling.
     */
    @Test
    void testConfigurationProfile() {
        logger.info("=== Testing Configuration Profile ===");
        
        assertEquals("test", peeGeeQProperties.getProfile());
        
        logger.info("✅ Configuration profile set correctly: {}", peeGeeQProperties.getProfile());
        logger.info("✅ Configuration profile test passed");
    }
}
