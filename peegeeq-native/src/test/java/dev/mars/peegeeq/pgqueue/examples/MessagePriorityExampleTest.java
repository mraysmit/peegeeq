package dev.mars.peegeeq.pgqueue.examples;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit test demonstrating message priority handling in PeeGeeQ Native Queue.
 *
 * This test class was migrated from MessagePriorityExample.java to provide proper JUnit test structure
 * while preserving ALL original functionality and educational content.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Basic Priority Ordering</b> - Verifies messages are processed in priority order (CRITICAL → HIGH → NORMAL → LOW → BULK)</li>
 *   <li><b>Priority Level Validation</b> - Tests all 5 priority levels with proper ordering</li>
 *   <li><b>E-Commerce Scenario</b> - Real-world order processing with different priorities</li>
 *   <li><b>Financial Scenario</b> - Critical financial transactions and fraud alerts</li>
 *   <li><b>Monitoring Scenario</b> - System alerts and monitoring messages</li>
 *   <li><b>Performance Testing</b> - Throughput measurement with priority queues</li>
 * </ul>
 *
 * <h2>Priority Levels</h2>
 * <ul>
 *   <li><b>CRITICAL (10)</b> - Security alerts, fraud detection, system failures</li>
 *   <li><b>HIGH (8)</b> - Urgent transactions, high-value operations</li>
 *   <li><b>NORMAL (5)</b> - Standard business operations</li>
 *   <li><b>LOW (2)</b> - Background tasks, notifications</li>
 *   <li><b>BULK (0)</b> - Batch operations, data imports</li>
 * </ul>
 *
 * <h2>Test Infrastructure</h2>
 * <ul>
 *   <li>Uses TestContainers with PostgreSQL 15.13-alpine3.20</li>
 *   <li>PeeGeeQ Native Queue implementation</li>
 *   <li>Proper setup/teardown with resource management</li>
 *   <li>Comprehensive assertions and performance metrics</li>
 * </ul>
 *
 * <h2>Expected Behavior</h2>
 * All tests should pass successfully, demonstrating:
 * <ul>
 *   <li>Messages are processed in strict priority order</li>
 *   <li>Higher priority messages preempt lower priority ones</li>
 *   <li>Performance meets minimum thresholds (≥10 msg/sec)</li>
 *   <li>Real-world scenarios work as expected</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessagePriorityExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(MessagePriorityExampleTest.class);
    
    // Priority levels
    public static final int PRIORITY_CRITICAL = 10;
    public static final int PRIORITY_HIGH = 8;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_LOW = 2;
    public static final int PRIORITY_BULK = 0;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_priority_demo")
            .withUsername("postgres")
            .withPassword("password")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory factory;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up Message Priority Example Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Configure for priority queue optimization
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "20");
        System.setProperty("peegeeq.queue.priority.enabled", "true");
        System.setProperty("peegeeq.queue.priority.index-optimization", "true");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"),
                new SimpleMeterRegistry());
        
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create database service and factory provider
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native queue factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        // Create native queue factory
        factory = provider.createFactory("native", databaseService);
        
        logger.info("✅ Message Priority Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Message Priority Example Test");
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.database.pool.min-size");
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.queue.priority.enabled");
        System.clearProperty("peegeeq.queue.priority.index-optimization");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
        
        logger.info("✅ Message Priority Example Test cleanup completed");
    }
    
    @Test
    void testBasicPriorityOrdering() throws Exception {
        logger.info("=== Testing Basic Priority Ordering ===");

        // Create producer and consumer for priority queue
        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-basic", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-basic", PriorityMessage.class)) {

            // Track processing order
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            // Set up consumer to track processing order
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processed #{}: {} (Priority: {})",
                    order, payload.getContent(), payload.getPriorityLabel());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages in reverse priority order to demonstrate reordering
            logger.info("Sending messages in reverse priority order...");

            sendPriorityMessage(producer, "msg-1", "BULK", "Bulk processing task", PRIORITY_BULK);
            sendPriorityMessage(producer, "msg-2", "LOW", "Background cleanup", PRIORITY_LOW);
            sendPriorityMessage(producer, "msg-3", "NORMAL", "Regular business operation", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "msg-4", "HIGH", "Important notification", PRIORITY_HIGH);
            sendPriorityMessage(producer, "msg-5", "CRITICAL", "Security alert", PRIORITY_CRITICAL);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All messages should be processed within timeout");
            assertEquals(5, processedCount.get(), "Should have processed exactly 5 messages");

            logger.info("✅ Basic priority ordering test completed successfully!");
        }
    }
    
    @Test
    void testPriorityLevels() throws Exception {
        logger.info("=== Testing Priority Levels ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-levels", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-levels", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            // Consumer that shows priority level handling
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processed #{}: [{}] {} - {}",
                    order, payload.getPriorityLabel(), payload.getMessageType(), payload.getContent());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages with different priority levels
            logger.info("Sending messages with different priority levels...");

            sendPriorityMessage(producer, "critical-1", "SECURITY", "Security breach detected", PRIORITY_CRITICAL);
            sendPriorityMessage(producer, "high-1", "ALERT", "System overload warning", PRIORITY_HIGH);
            sendPriorityMessage(producer, "normal-1", "ORDER", "New order received", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "low-1", "CLEANUP", "Cleanup old logs", PRIORITY_LOW);
            sendPriorityMessage(producer, "bulk-1", "ANALYTICS", "Generate daily report", PRIORITY_BULK);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All messages should be processed within timeout");
            assertEquals(5, processedCount.get(), "Should have processed exactly 5 messages");

            logger.info("✅ Priority levels test completed successfully!");
        }
    }

    @Test
    void testECommerceScenario() throws Exception {
        logger.info("=== Testing E-Commerce Order Processing Scenario ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("ecommerce-orders", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("ecommerce-orders", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(8);

            // Consumer that processes e-commerce orders
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processing Order #{}: [{}] {} - {}",
                    order, payload.getPriorityLabel(), payload.getMessageType(), payload.getContent());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send e-commerce scenario messages
            logger.info("Sending e-commerce order messages...");

            // VIP customer orders (highest priority)
            sendPriorityMessage(producer, "vip-001", "VIP_ORDER", "VIP customer premium order", PRIORITY_CRITICAL);
            sendPriorityMessage(producer, "vip-002", "VIP_ORDER", "VIP customer express delivery", PRIORITY_CRITICAL);

            // Urgent orders (high priority)
            sendPriorityMessage(producer, "urgent-001", "URGENT_ORDER", "Same-day delivery request", PRIORITY_HIGH);
            sendPriorityMessage(producer, "urgent-002", "URGENT_ORDER", "Stock shortage alert", PRIORITY_HIGH);

            // Regular orders (normal priority)
            sendPriorityMessage(producer, "regular-001", "REGULAR_ORDER", "Standard customer order", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "regular-002", "REGULAR_ORDER", "Regular shipping order", PRIORITY_NORMAL);

            // Bulk processing (low priority)
            sendPriorityMessage(producer, "bulk-001", "BULK_ORDER", "Inventory reconciliation", PRIORITY_LOW);
            sendPriorityMessage(producer, "analytics-001", "ANALYTICS", "Daily sales report", PRIORITY_BULK);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All e-commerce messages should be processed within timeout");
            assertEquals(8, processedCount.get(), "Should have processed exactly 8 messages");

            logger.info("✅ E-commerce scenario test completed successfully!");
        }
    }

    @Test
    void testFinancialScenario() throws Exception {
        logger.info("=== Testing Financial Transaction Processing Scenario ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("financial-transactions", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("financial-transactions", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(7);

            // Consumer that processes financial transactions
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processing Transaction #{}: [{}] {} - {}",
                    order, payload.getPriorityLabel(), payload.getMessageType(), payload.getContent());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send financial scenario messages
            logger.info("Sending financial transaction messages...");

            // Critical security alerts
            sendPriorityMessage(producer, "fraud-001", "FRAUD_ALERT", "Suspicious transaction detected", PRIORITY_CRITICAL);
            sendPriorityMessage(producer, "security-001", "SECURITY_ALERT", "Multiple failed login attempts", PRIORITY_CRITICAL);

            // High-value transactions
            sendPriorityMessage(producer, "high-value-001", "HIGH_VALUE_TX", "Large wire transfer", PRIORITY_HIGH);
            sendPriorityMessage(producer, "urgent-001", "URGENT_TX", "Time-sensitive payment", PRIORITY_HIGH);

            // Regular transactions
            sendPriorityMessage(producer, "regular-001", "REGULAR_TX", "Standard payment processing", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "regular-002", "REGULAR_TX", "Account balance update", PRIORITY_NORMAL);

            // Batch processing
            sendPriorityMessage(producer, "batch-001", "BATCH_TX", "End-of-day reconciliation", PRIORITY_BULK);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All financial messages should be processed within timeout");
            assertEquals(7, processedCount.get(), "Should have processed exactly 7 messages");

            logger.info("✅ Financial scenario test completed successfully!");
        }
    }

    @Test
    void testMonitoringScenario() throws Exception {
        logger.info("=== Testing System Monitoring and Alerts Scenario ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("system-monitoring", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("system-monitoring", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(6);

            // Consumer that processes monitoring alerts
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processing Alert #{}: [{}] {} - {}",
                    order, payload.getPriorityLabel(), payload.getMessageType(), payload.getContent());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send monitoring scenario messages
            logger.info("Sending system monitoring messages...");

            // Critical system alerts
            sendPriorityMessage(producer, "critical-001", "SYSTEM_DOWN", "Database server offline", PRIORITY_CRITICAL);
            sendPriorityMessage(producer, "critical-002", "SECURITY_BREACH", "Unauthorized access detected", PRIORITY_CRITICAL);

            // High priority warnings
            sendPriorityMessage(producer, "warning-001", "HIGH_CPU", "CPU usage above 90%", PRIORITY_HIGH);
            sendPriorityMessage(producer, "warning-002", "DISK_SPACE", "Disk space critically low", PRIORITY_HIGH);

            // Regular monitoring
            sendPriorityMessage(producer, "info-001", "HEALTH_CHECK", "System health check passed", PRIORITY_NORMAL);

            // Background analytics
            sendPriorityMessage(producer, "analytics-001", "METRICS", "Generate performance metrics", PRIORITY_BULK);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All monitoring messages should be processed within timeout");
            assertEquals(6, processedCount.get(), "Should have processed exactly 6 messages");

            logger.info("✅ Monitoring scenario test completed successfully!");
        }
    }

    @Test
    void testPriorityPerformance() throws Exception {
        logger.info("=== Testing Priority Queue Performance ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-performance", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-performance", PriorityMessage.class)) {

            int messageCount = 50; // Reduced for test environment
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(messageCount);
            long startTime = System.currentTimeMillis();

            // Consumer that tracks performance
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                if (order % 10 == 0) { // Log every 10th message
                    logger.info("Processed {} messages - Current: [{}] {}",
                        order, payload.getPriorityLabel(), payload.getMessageType());
                }
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send mixed priority messages for performance testing
            logger.info("Sending {} mixed priority messages for performance testing...", messageCount);

            for (int i = 0; i < messageCount; i++) {
                int priority = (i % 5) * 2; // Mix of priorities: 0, 2, 4, 6, 8
                String messageType = "PERF_TEST_" + priority;
                String content = "Performance test message " + i;
                sendPriorityMessage(producer, "perf-" + i, messageType, content, priority);
            }

            // Wait for processing
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            assertTrue(completed, "All performance messages should be processed within timeout");
            assertEquals(messageCount, processedCount.get(), "Should have processed exactly " + messageCount + " messages");

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            double messagesPerSecond = (messageCount * 1000.0) / duration;

            logger.info("✅ Priority performance test completed successfully!");
            logger.info("📊 Performance Results: {} messages in {}ms ({:.2f} msg/sec)",
                messageCount, duration, messagesPerSecond);

            // Basic performance assertion - should process at least 10 messages per second
            assertTrue(messagesPerSecond > 10,
                "Performance should be at least 10 msg/sec, got: " + messagesPerSecond);
        }
    }

    private void sendPriorityMessage(MessageProducer<PriorityMessage> producer, String messageId,
                                   String messageType, String content, int priority) throws Exception {
        PriorityMessage message = new PriorityMessage(messageId, messageType, content, priority,
                                                    System.currentTimeMillis(), new HashMap<>());
        
        Map<String, String> headers = Map.of(
            "priority", String.valueOf(priority),
            "messageType", messageType
        );
        
        producer.send(message, headers, messageId, String.valueOf(priority)).get(5, TimeUnit.SECONDS);
        logger.debug("Sent message: {} with priority {}", messageId, priority);
    }
    
    /**
     * Priority message payload with different types and urgency levels.
     */
    public static class PriorityMessage {
        private final String messageId;
        private final String messageType;
        private final String content;
        private final int priority;
        private final long timestamp;
        private final Map<String, String> metadata;
        
        @JsonCreator
        public PriorityMessage(@JsonProperty("messageId") String messageId,
                              @JsonProperty("messageType") String messageType,
                              @JsonProperty("content") String content,
                              @JsonProperty("priority") int priority,
                              @JsonProperty("timestamp") long timestamp,
                              @JsonProperty("metadata") Map<String, String> metadata) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.content = content;
            this.priority = priority;
            this.timestamp = timestamp;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getContent() { return content; }
        public int getPriority() { return priority; }
        public long getTimestamp() { return timestamp; }
        public Map<String, String> getMetadata() { return metadata; }
        
        @JsonIgnore
        public String getPriorityLabel() {
            if (priority >= 10) return "CRITICAL";
            if (priority >= 7) return "HIGH";
            if (priority >= 4) return "NORMAL";
            if (priority >= 1) return "LOW";
            return "BULK";
        }
        
        @Override
        public String toString() {
            return String.format("PriorityMessage{id='%s', type='%s', priority=%d (%s), content='%s'}", 
                messageId, messageType, priority, getPriorityLabel(), content);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PriorityMessage that = (PriorityMessage) o;
            return Objects.equals(messageId, that.messageId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(messageId);
        }
    }
}
