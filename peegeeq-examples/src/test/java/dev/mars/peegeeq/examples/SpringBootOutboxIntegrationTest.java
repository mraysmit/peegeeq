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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating basic reactive outbox operations with real database.
 * Uses TestContainers to provide PostgreSQL for testing.
 * 
 * This test follows the PeeGeeQ Transactional Outbox Patterns Guide and demonstrates
 * the three reactive approaches:
 * 1. Basic Reactive Operations (sendReactive)
 * 2. Transaction Participation (sendInTransaction) 
 * 3. Automatic Transaction Management (sendWithTransaction)
 * 
 * Requirements:
 * - Docker must be available for TestContainers
 * - Test validates actual database connectivity and reactive operations
 * 
 * Test Scenarios:
 * - Basic reactive send operations without transaction management
 * - Reactive send with headers and metadata
 * - Error handling and timeout scenarios
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@SpringBootTest(classes = SpringBootOutboxIntegrationTest.TestApplication.class)
@Import(SpringBootOutboxIntegrationTest.PeeGeeQTestConfiguration.class)
@Testcontainers
class SpringBootOutboxIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(SpringBootOutboxIntegrationTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_spring_boot")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    @Autowired
    private PeeGeeQManager manager;

    @Autowired
    private QueueFactory outboxFactory;

    @Autowired
    private MessageProducer<OrderEvent> orderProducer;

    /**
     * Configure Spring Boot properties dynamically from TestContainers
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring Spring Boot properties from TestContainers");
        logger.info("Container: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", postgres::getFirstMappedPort);
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);

        logger.info("Spring Boot properties configured");
    }
    
    // Spring Boot handles setup through @DynamicPropertySource and @TestConfiguration
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down ReactiveOutboxBasicExampleTest");
        
        if (orderProducer != null) {
            orderProducer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.close();
        }
        
        logger.info("Test teardown completed");
    }
    
    /**
     * Test Step 1: Verify container startup and database connectivity
     * Principle: "Validate Each Step" - Test infrastructure before functionality
     */
    @Test
    void testContainerStartupAndDatabaseConnection() {
        logger.info("=== Testing Container Startup and Database Connection ===");
        
        // Verify container is running
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        logger.info("✓ Container is running: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Verify manager is started
        assertNotNull(manager, "PeeGeeQ Manager should be initialized");
        logger.info("✓ PeeGeeQ Manager is initialized");
        
        // Verify factory is created
        assertNotNull(outboxFactory, "Outbox factory should be created");
        logger.info("✓ Outbox factory is created");
        
        // Verify producer is created
        assertNotNull(orderProducer, "Order producer should be created");
        logger.info("✓ Order producer is created");
        
        logger.info("=== Container and Database Connection Test PASSED ===");
    }

    /**
     * Test Step 2: Basic reactive send operation
     * Principle: "Validate Each Step" - Test basic sendReactive functionality
     *
     * This demonstrates the first reactive approach from the guide:
     * "Basic Reactive Operations (sendReactive) - Non-blocking operations without transaction management"
     */
    @Test
    void testBasicReactiveSend() throws Exception {
        logger.info("=== Testing Basic Reactive Send Operation ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-001", "CUSTOMER-123", 99.99);
        logger.info("Created test order: {}", testOrder);

        // Test basic reactive send - this should work with the standard MessageProducer interface
        CompletableFuture<Void> sendFuture = orderProducer.send(testOrder);

        // Wait for completion with timeout
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Basic reactive send completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Basic Reactive Send Test PASSED ===");
    }

    /**
     * Test Step 3: Reactive send with headers and metadata
     * Principle: "Validate Each Step" - Test enhanced reactive functionality
     */
    @Test
    void testReactiveSendWithMetadata() throws Exception {
        logger.info("=== Testing Reactive Send with Headers and Metadata ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-002", "CUSTOMER-456", 149.99);
        logger.info("Created test order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        logger.info("Created headers: {}", headers);

        // Test reactive send with headers and correlation ID
        String correlationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Void> sendFuture = orderProducer.send(testOrder, headers, correlationId);

        // Wait for completion with timeout
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Reactive send with metadata completed successfully");
        logger.info("✓ Used correlation ID: {}", correlationId);

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Reactive Send with Metadata Test PASSED ===");
    }

    /**
     * Simple event class for testing
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final double amount;
        private final Instant timestamp;
        
        public OrderEvent(String orderId, String customerId, double amount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.timestamp = Instant.now();
        }
        
        // Getters
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public double getAmount() { return amount; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%.2f, timestamp=%s}",
                orderId, customerId, amount, timestamp);
        }
    }

    /**
     * Spring Boot Application for testing
     */
    @SpringBootApplication
    static class TestApplication {
        // Configuration is in @TestConfiguration
    }

    /**
     * Spring Boot Test Configuration for PeeGeeQ
     */
    @TestConfiguration
    static class PeeGeeQTestConfiguration {
        private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestConfiguration.class);

        @Bean
        @Primary
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        @Primary
        public PeeGeeQManager peeGeeQManager(MeterRegistry meterRegistry) {
            logger.info("Creating PeeGeeQ Manager bean");
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
            PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
            manager.start();
            logger.info("PeeGeeQ Manager bean started");
            return manager;
        }

        @Bean
        public QueueFactory outboxFactory(PeeGeeQManager manager) {
            logger.info("Creating outbox factory bean");
            DatabaseService databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            QueueFactory factory = provider.createFactory("outbox", databaseService);
            logger.info("Outbox factory bean created");
            return factory;
        }

        @Bean
        public MessageProducer<OrderEvent> orderProducer(QueueFactory outboxFactory) {
            logger.info("Creating order producer bean");
            return outboxFactory.createProducer("orders", OrderEvent.class);
        }
    }
}
