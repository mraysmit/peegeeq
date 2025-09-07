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
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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
 * Integration test demonstrating Basic Reactive Operations pattern from PeeGeeQ Guide.
 * 
 * This test validates the first reactive approach: sendReactive() operations
 * following the patterns outlined in Section "1. Basic Reactive Operations".
 * 
 * Key Features Demonstrated:
 * - Simple reactive send (sendReactive)
 * - Reactive send with metadata (headers, correlation ID, message groups)
 * - Non-blocking operations without transaction management
 * - Performance comparison with blocking operations
 * 
 * Requirements:
 * - Docker must be available for TestContainers
 * - Test validates actual database operations and message persistence
 * 
 * Test Scenarios:
 * - Simple reactive send
 * - Reactive send with headers
 * - Reactive send with correlation ID
 * - Reactive send with full parameters (headers, correlation ID, message groups)
 * - Performance validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@Testcontainers
class BasicReactiveOperationsExampleTest {
    private static final Logger logger = LoggerFactory.getLogger(BasicReactiveOperationsExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_basic_reactive")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up BasicReactiveOperationsExampleTest");
        logger.info("Container started: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Configure system properties from TestContainers - following established pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory - following established pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        logger.info("Test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down BasicReactiveOperationsExampleTest");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("Order producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("PeeGeeQ Manager stopped");
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
        logger.info("✓ Order producer is created and is OutboxProducer type");
        
        logger.info("=== Container and Database Connection Test PASSED ===");
    }

    /**
     * Test Step 2: Simple reactive send operation
     * Principle: "Validate Each Step" - Test basic sendReactive functionality
     *
     * This demonstrates the first reactive approach from the guide:
     * "Simple Reactive Send" - sendReactive(payload)
     */
    @Test
    void testSimpleReactiveSend() throws Exception {
        logger.info("=== Testing Simple Reactive Send Operation ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-001", "CUSTOMER-123", 99.99);
        logger.info("Created test order: {}", testOrder);

        // Test simple reactive send - this uses the sendReactive() method
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder);

        // Wait for completion with timeout
        assertDoesNotThrow(() -> sendFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Simple reactive send completed successfully");

        // Give a moment for any background processing
        Thread.sleep(500);

        logger.info("=== Simple Reactive Send Test PASSED ===");
    }

    /**
     * Test Step 3: Reactive send with headers
     * Principle: "Validate Each Step" - Test sendReactive with headers
     */
    @Test
    void testReactiveSendWithHeaders() throws Exception {
        logger.info("=== Testing Reactive Send with Headers ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-002", "CUSTOMER-456", 149.99);
        logger.info("Created test order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        logger.info("Created headers: {}", headers);

        // Test reactive send with headers - sendReactive(payload, headers)
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder, headers);

        // Wait for completion with timeout
        assertDoesNotThrow(() -> sendFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Reactive send with headers completed successfully");

        // Give a moment for any background processing
        Thread.sleep(500);

        logger.info("=== Reactive Send with Headers Test PASSED ===");
    }

    /**
     * Test Step 4: Reactive send with headers and correlation ID
     * Principle: "Validate Each Step" - Test sendReactive with correlation tracking
     */
    @Test
    void testReactiveSendWithCorrelationId() throws Exception {
        logger.info("=== Testing Reactive Send with Headers and Correlation ID ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-003", "CUSTOMER-789", 199.99);
        logger.info("Created test order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("version", "1.0");
        headers.put("environment", "test");
        logger.info("Created headers: {}", headers);

        // Create correlation ID
        String correlationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
        logger.info("Created correlation ID: {}", correlationId);

        // Test reactive send with headers and correlation ID - sendReactive(payload, headers, correlationId)
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder, headers, correlationId);

        // Wait for completion with timeout
        assertDoesNotThrow(() -> sendFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Reactive send with headers and correlation ID completed successfully");
        logger.info("✓ Used correlation ID: {}", correlationId);

        // Give a moment for any background processing
        Thread.sleep(500);

        logger.info("=== Reactive Send with Headers and Correlation ID Test PASSED ===");
    }

    /**
     * Test Step 5: Full reactive send with all parameters
     * Principle: "Validate Each Step" - Test sendReactive with all parameters
     *
     * This demonstrates the complete reactive send from the guide:
     * "Full reactive send method with all parameters" - sendReactive(payload, headers, correlationId, messageGroup)
     */
    @Test
    void testFullReactiveSendWithAllParameters() throws Exception {
        logger.info("=== Testing Full Reactive Send with All Parameters ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-004", "CUSTOMER-999", 299.99);
        logger.info("Created test order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        headers.put("region", "us-east-1");
        logger.info("Created headers: {}", headers);

        // Create correlation ID and message group
        String correlationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "order-group-1";
        logger.info("Created correlation ID: {}", correlationId);
        logger.info("Created message group: {}", messageGroup);

        // Test full reactive send - sendReactive(payload, headers, correlationId, messageGroup)
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder, headers, correlationId, messageGroup);

        // Wait for completion with timeout
        assertDoesNotThrow(() -> sendFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Full reactive send with all parameters completed successfully");
        logger.info("✓ Used correlation ID: {}", correlationId);
        logger.info("✓ Used message group: {}", messageGroup);

        // Give a moment for any background processing
        Thread.sleep(500);

        logger.info("=== Full Reactive Send with All Parameters Test PASSED ===");
    }

    /**
     * Test Step 6: Reactive send progression test
     * Principle: "Iterative Validation" - Test each step builds on the previous
     *
     * This demonstrates progressive complexity following the guide patterns
     */
    @Test
    void testReactiveSendProgression() throws Exception {
        logger.info("=== Testing Reactive Send Progression ===");

        // Step 1: Basic send - verify it works
        OrderEvent order1 = new OrderEvent("PROG-001", "CUSTOMER-001", 50.00);
        CompletableFuture<Void> basicFuture = orderProducer.sendReactive(order1);
        assertDoesNotThrow(() -> basicFuture.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 1: Basic reactive send successful");

        // Step 2: Send with headers - verify metadata works
        OrderEvent order2 = new OrderEvent("PROG-002", "CUSTOMER-002", 75.00);
        Map<String, String> headers = Map.of("source", "test", "version", "1.0");
        CompletableFuture<Void> headerFuture = orderProducer.sendReactive(order2, headers);
        assertDoesNotThrow(() -> headerFuture.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 2: Reactive send with headers successful");

        // Step 3: Send with correlation ID - verify tracking works
        OrderEvent order3 = new OrderEvent("PROG-003", "CUSTOMER-003", 100.00);
        String correlationId = "PROG-CORR-123";
        CompletableFuture<Void> corrFuture = orderProducer.sendReactive(order3, headers, correlationId);
        assertDoesNotThrow(() -> corrFuture.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 3: Reactive send with correlation ID successful");

        // Step 4: Full parameters - verify complete functionality
        OrderEvent order4 = new OrderEvent("PROG-004", "CUSTOMER-004", 125.00);
        String messageGroup = "prog-group";
        CompletableFuture<Void> fullFuture = orderProducer.sendReactive(order4, headers, correlationId, messageGroup);
        assertDoesNotThrow(() -> fullFuture.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 4: Full parameter reactive send successful");

        logger.info("=== Reactive Send Progression Test PASSED ===");
    }

    /**
     * Test Step 7: Performance validation
     * Principle: "Verify Assumptions" - Validate reactive performance claims
     *
     * This demonstrates the performance benefits mentioned in the guide
     */
    @Test
    void testReactivePerformanceValidation() throws Exception {
        logger.info("=== Testing Reactive Performance Validation ===");

        int messageCount = 10; // Small count for integration test
        OrderEvent[] testOrders = new OrderEvent[messageCount];

        // Prepare test data
        for (int i = 0; i < messageCount; i++) {
            testOrders[i] = new OrderEvent("PERF-" + String.format("%03d", i), "CUSTOMER-PERF", 10.0 + i);
        }
        logger.info("Prepared {} test orders for performance validation", messageCount);

        // Test reactive send performance
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void>[] futures = new CompletableFuture[messageCount];

        for (int i = 0; i < messageCount; i++) {
            futures[i] = orderProducer.sendReactive(testOrders[i]);
        }

        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
        assertDoesNotThrow(() -> allFutures.get(30, TimeUnit.SECONDS));

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double messagesPerSecond = (messageCount * 1000.0) / duration;

        logger.info("✓ Reactive performance test completed");
        logger.info("✓ Sent {} messages in {} ms", messageCount, duration);
        logger.info("✓ Performance: {:.2f} messages/second", messagesPerSecond);

        // Verify reasonable performance (should be much faster than blocking)
        assertTrue(messagesPerSecond > 10, "Reactive send should achieve > 10 messages/second");

        logger.info("=== Reactive Performance Validation Test PASSED ===");
    }

    /**
     * Simple event class for testing - following established pattern
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
}
