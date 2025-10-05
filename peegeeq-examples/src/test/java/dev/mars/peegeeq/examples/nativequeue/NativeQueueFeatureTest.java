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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
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
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
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
        // Clear system properties

        clearSystemProperties();

    }
    
    @AfterEach
    void tearDown() {
        if (nativeFactory != null) {
            try {
                nativeFactory.close();
                // Clear system properties

                clearSystemProperties();

            } catch (Exception e) {
                logger.error("Error closing native factory", e);
                // Clear system properties

                clearSystemProperties();

            }
            // Clear system properties

            clearSystemProperties();

        }
        if (outboxFactory != null) {
            try {
                outboxFactory.close();
                // Clear system properties

                clearSystemProperties();

            } catch (Exception e) {
                logger.error("Error closing outbox factory", e);
                // Clear system properties

                clearSystemProperties();

            }
            // Clear system properties

            clearSystemProperties();

        }
        if (manager != null) {
            manager.stop();
            // Clear system properties

            clearSystemProperties();

        }
        logger.info("Native queue feature test teardown completed");
        // Clear system properties

        clearSystemProperties();

    }
    
    @Test
    void testNativeFactoryCreation() {
        // Verify that native factory is properly created and identified
        assertNotNull(nativeFactory);
        assertEquals("native", nativeFactory.getImplementationType());
        assertTrue(nativeFactory.isHealthy());
        
        logger.info("✅ Native factory creation test passed");
        // Clear system properties

        clearSystemProperties();

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
        // Clear system properties

        clearSystemProperties();

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
            // Clear system properties

            clearSystemProperties();

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
        logger.info("Native queue latency: {    // Clear system properties
    clearSystemProperties();
}ms", latency);
        assertTrue(latency < 5000, "Native queue should have low latency (< 5s)");
        
        producer.close();
        consumer.close();
        
        logger.info("✅ Native real-time messaging test passed");
        // Clear system properties

        clearSystemProperties();

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
            logger.info("Member-1 processed: {    // Clear system properties
    clearSystemProperties();
}", message.getPayload());
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });

        consumerGroup.addConsumer("member-2", message -> {
            processedCount.incrementAndGet();
            latch.countDown();
            logger.info("Member-2 processed: {    // Clear system properties
    clearSystemProperties();
}", message.getPayload());
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });
        
        // Start the consumer group
        consumerGroup.start();
        
        // Send multiple messages
        for (int i = 0; i < 5; i++) {
            producer.send("Group message " + i).get(2, TimeUnit.SECONDS);
            // Clear system properties

            clearSystemProperties();

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
        // Clear system properties

        clearSystemProperties();

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
            // Clear system properties

            clearSystemProperties();

        });
        
        // Send messages concurrently
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < messageCount; i++) {
            final int messageId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    producer.send("Concurrent message " + messageId).get(5, TimeUnit.SECONDS);
                    // Clear system properties

                    clearSystemProperties();

                } catch (Exception e) {
                    logger.error("Failed to send message " + messageId, e);
                    // Clear system properties

                    clearSystemProperties();

                }
                // Clear system properties

                clearSystemProperties();

            }, executor);
            futures.add(future);
            // Clear system properties

            clearSystemProperties();

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
        // Clear system properties

        clearSystemProperties();

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
            // Clear system properties

            clearSystemProperties();

        }
        
        // Verify all are healthy
        assertTrue(nativeFactory.isHealthy());
        
        // Close all resources
        for (MessageProducer<String> producer : producers) {
            producer.close();
            // Clear system properties

            clearSystemProperties();

        }
        for (MessageConsumer<String> consumer : consumers) {
            consumer.close();
            // Clear system properties

            clearSystemProperties();

        }
        
        // Factory should still be healthy
        assertTrue(nativeFactory.isHealthy());
        
        logger.info("✅ Native factory resource management test passed");
        // Clear system properties

        clearSystemProperties();

    }
    
    /**
     * Test message class for native queue testing
     */
    public static class TestEvent {
        private String id;
        private String type;
        private Instant timestamp;
        private String data;
        
        public TestEvent() {    // Clear system properties
    clearSystemProperties();
}
        
        public TestEvent(String id, String type, String data) {
            this.id = id;
            this.type = type;
            this.data = data;
            this.timestamp = Instant.now();
            // Clear system properties

            clearSystemProperties();

        }
        
        // Getters and setters
        public String getId() { return id;     // Clear system properties
     clearSystemProperties();
 }
        public void setId(String id) { this.id = id;     // Clear system properties
     clearSystemProperties();
 }
        public String getType() { return type;     // Clear system properties
     clearSystemProperties();
 }
        public void setType(String type) { this.type = type;     // Clear system properties
     clearSystemProperties();
 }
        public Instant getTimestamp() { return timestamp;     // Clear system properties
     clearSystemProperties();
 }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp;     // Clear system properties
     clearSystemProperties();
 }
        public String getData() { return data;     // Clear system properties
     clearSystemProperties();
 }
        public void setData(String data) { this.data = data;     // Clear system properties
     clearSystemProperties();
 }
        // Clear system properties

        clearSystemProperties();

    }
    // Clear system properties

    clearSystemProperties();

/**


 * Clear system properties after test completion


 */


private void clearSystemProperties() {


    System.clearProperty("peegeeq.database.host");


    System.clearProperty("peegeeq.database.port");


    System.clearProperty("peegeeq.database.name");


    System.clearProperty("peegeeq.database.username");


    System.clearProperty("peegeeq.database.password");


}

}
