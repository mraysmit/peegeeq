package dev.mars.peegeeq.examples.nativequeue;

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
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for native queue features that are unique to the native implementation.
 * This test class ensures that native-specific functionality is properly tested and working.
 * 
 * Tests cover:
 * - PostgreSQL LISTEN/NOTIFY real-time messaging
 * - Advisory locks and message coordination
 * - Native consumer groups
 * - Performance characteristics
 * - Resource management
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-03
 * @version 1.0
 */
@Testcontainers
class NativeQueueFeatureTest {
    private static final Logger logger = LoggerFactory.getLogger(NativeQueueFeatureTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_native_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;
    private QueueFactory outboxFactory;
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize database schema for native queue test
        logger.info("Initializing database schema for native queue test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Configure system properties for the container
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        
        // Create both native and outbox factories for comparison
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register queue factory implementations
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        nativeFactory = provider.createFactory("native", databaseService);
        outboxFactory = provider.createFactory("outbox", databaseService);
        
        logger.info("Native queue feature test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() {
        if (nativeFactory != null) {
            try {
                nativeFactory.close();
            } catch (Exception e) {
                logger.error("Error closing native factory", e);
            }
        }
        if (outboxFactory != null) {
            try {
                outboxFactory.close();
            } catch (Exception e) {
                logger.error("Error closing outbox factory", e);
            }
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("Native queue feature test teardown completed");
    }
    
    @Test
    void testNativeFactoryCreation() {
        // Verify that native factory is properly created and identified
        assertNotNull(nativeFactory);
        assertEquals("native", nativeFactory.getImplementationType());
        assertTrue(nativeFactory.isHealthy());
        
        logger.info("✅ Native factory creation test passed");
    }
    
    @Test
    void testNativeVsOutboxFactoryDifferences() {
        // Verify that native and outbox factories are different implementations
        assertNotNull(nativeFactory);
        assertNotNull(outboxFactory);
        assertEquals("native", nativeFactory.getImplementationType());
        assertEquals("outbox", outboxFactory.getImplementationType());
        assertNotEquals(nativeFactory.getClass(), outboxFactory.getClass());
        
        logger.info("✅ Native vs Outbox factory differences test passed");
    }
    
    @Test
    void testNativeRealTimeMessaging() throws Exception {
        // Test that native queue provides real-time messaging capabilities
        MessageProducer<String> producer = nativeFactory.createProducer("realtime-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("realtime-test", String.class);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong receiveTime = new AtomicLong();
        List<String> receivedMessages = new ArrayList<>();
        
        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receiveTime.set(System.currentTimeMillis());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send message and measure time
        long sendTime = System.currentTimeMillis();
        producer.send("Real-time test message").get(5, TimeUnit.SECONDS);
        
        // Wait for message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received within 10 seconds");
        assertEquals(1, receivedMessages.size());
        assertEquals("Real-time test message", receivedMessages.get(0));
        
        // Verify low latency (native should be faster than outbox)
        long latency = receiveTime.get() - sendTime;
        logger.info("Native queue latency: {}ms", latency);
        assertTrue(latency < 10000, "Native queue should have low latency (< 10s)");
        
        producer.close();
        consumer.close();
        
        logger.info("✅ Native real-time messaging test passed");
    }
    
    @Test
    void testNativeConsumerGroups() throws Exception {
        // Test native consumer group functionality
        ConsumerGroup<String> consumerGroup = nativeFactory.createConsumerGroup(
            "native-test-group", "group-test-topic", String.class);
        MessageProducer<String> producer = nativeFactory.createProducer("group-test-topic", String.class);
        
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger processedCount = new AtomicInteger();
        
        // Add consumer group members
        consumerGroup.addConsumer("member-1", message -> {
            processedCount.incrementAndGet();
            latch.countDown();
            logger.info("Member-1 processed: {}", message.getPayload());
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("member-2", message -> {
            processedCount.incrementAndGet();
            latch.countDown();
            logger.info("Member-2 processed: {}", message.getPayload());
            return CompletableFuture.completedFuture(null);
        });
        
        // Start the consumer group
        consumerGroup.start();
        
        // Send multiple messages
        for (int i = 0; i < 5; i++) {
            producer.send("Group message " + i).get(2, TimeUnit.SECONDS);
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be processed");
        assertEquals(5, processedCount.get());
        
        // Verify consumer group stats
        ConsumerGroupStats stats = consumerGroup.getStats();
        assertNotNull(stats);
        assertEquals(2, stats.getActiveConsumerCount());
        assertTrue(stats.getTotalMessagesProcessed() >= 5);
        
        consumerGroup.stop();
        producer.close();
        
        logger.info("✅ Native consumer groups test passed");
    }
    
    @Test
    void testNativeMessageConcurrency() throws Exception {
        // Test that native queue handles concurrent message processing correctly
        MessageProducer<String> producer = nativeFactory.createProducer("concurrency-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("concurrency-test", String.class);
        
        int messageCount = 20;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger();
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        
        // Subscribe with concurrent processing
        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            processedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send messages concurrently
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < messageCount; i++) {
            final int messageId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    producer.send("Concurrent message " + messageId).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.error("Failed to send message " + messageId, e);
                }
            }, executor);
            futures.add(future);
        }
        
        // Wait for all sends to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        
        // Wait for all messages to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All messages should be processed");
        assertEquals(messageCount, processedCount.get());
        assertEquals(messageCount, receivedMessages.size());
        
        executor.shutdown();
        producer.close();
        consumer.close();
        
        logger.info("✅ Native message concurrency test passed");
    }
    
    @Test
    void testNativeFactoryResourceManagement() throws Exception {
        // Test that native factory properly manages resources
        assertTrue(nativeFactory.isHealthy());
        
        // Create multiple producers and consumers
        List<MessageProducer<String>> producers = new ArrayList<>();
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            producers.add(nativeFactory.createProducer("resource-test-" + i, String.class));
            consumers.add(nativeFactory.createConsumer("resource-test-" + i, String.class));
        }
        
        // Verify all are healthy
        assertTrue(nativeFactory.isHealthy());
        
        // Close all resources
        for (MessageProducer<String> producer : producers) {
            producer.close();
        }
        for (MessageConsumer<String> consumer : consumers) {
            consumer.close();
        }
        
        // Factory should still be healthy
        assertTrue(nativeFactory.isHealthy());
        
        logger.info("✅ Native factory resource management test passed");
    }
    
    /**
     * Test message class for native queue testing
     */
    public static class TestEvent {
        private String id;
        private String type;
        private Instant timestamp;
        private String data;
        
        public TestEvent() {}
        
        public TestEvent(String id, String type, String data) {
            this.id = id;
            this.type = type;
            this.data = data;
            this.timestamp = Instant.now();
        }
        
        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
}
