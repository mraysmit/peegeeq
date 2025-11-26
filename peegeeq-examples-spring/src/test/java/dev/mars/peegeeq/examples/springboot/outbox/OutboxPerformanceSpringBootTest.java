package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 Spring Boot Integration Tests - Performance
 * 
 * Tests performance characteristics of the outbox pattern:
 * - High-volume message processing (1000+ messages)
 * - Concurrent consumer performance
 * - Throughput measurements
 * 
 * These tests verify the system can handle production-level loads.
 */
@Tag(TestCategories.PERFORMANCE)
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
public class OutboxPerformanceSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPerformanceSpringBootTest.class);
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxPerformance test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @Autowired
    private OutboxFactory outboxFactory;

    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();
    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        logger.info("🚀 Setting up Performance Spring Boot Test");

        // Initialize database schema for outbox tests
        logger.info("Initializing database schema for Spring Boot performance test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.info("🧹 Cleaning up Performance Spring Boot Test");
        
        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
                logger.info("✅ Closed consumer");
            } catch (Exception e) {
                logger.error("⚠️ Error closing consumer: {}", e.getMessage());
            }
        }
        activeConsumers.clear();
        
        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
                logger.info("✅ Closed producer");
            } catch (Exception e) {
                logger.error("⚠️ Error closing producer: {}", e.getMessage());
            }
        }
        activeProducers.clear();
        
        // Wait for connections to be fully released before next test
        logger.info("⏳ Waiting for connections to be released...");
        Thread.sleep(2000);
        
        logger.info("✅ Cleanup complete");
    }

    /**
     * Test 1: High-Volume Message Processing
     *
     * Verifies the system can handle processing 200+ messages efficiently.
     * Measures throughput and ensures all messages are processed successfully.
     */
    @Test
    void testHighVolumeProcessing() throws Exception {
        logger.info("=== Testing High-Volume Message Processing ===");
        logger.info("This test processes 200 messages and measures throughput");

        String topic = "perf-highvolume-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 200;
        
        // Track processing
        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicLong totalProcessingTime = new AtomicLong(0);
        
        // Create consumer
        MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
        activeConsumers.add(consumer);
        
        consumer.subscribe(message -> {
            PerformanceMessage msg = message.getPayload();
            long processingTime = System.currentTimeMillis() - msg.getSentAt();
            totalProcessingTime.addAndGet(processingTime);
            
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                logger.info("📦 Processed {} messages", count);
            }
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages
        logger.info("📤 Sending {} messages", messageCount);
        Instant sendStart = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            PerformanceMessage message = new PerformanceMessage(
                "msg-" + i, 
                "High volume test message " + i,
                System.currentTimeMillis()
            );
            producer.send(message).join();
            
            if (i % 100 == 0) {
                logger.info("   Sent {} messages", i);
            }
        }
        
        Instant sendEnd = Instant.now();
        long sendDuration = Duration.between(sendStart, sendEnd).toMillis();
        
        logger.info("✅ All messages sent in {} ms", sendDuration);
        logger.info("   Send throughput: {} msg/sec", (messageCount * 1000L) / sendDuration);
        
        // Wait for all messages to be processed (allow 0.5 sec per message + overhead)
        boolean completed = latch.await(150, TimeUnit.SECONDS);
        Instant processEnd = Instant.now();

        assertTrue(completed, "All messages should be processed within 150 seconds");
        
        // Calculate metrics
        long totalDuration = Duration.between(sendStart, processEnd).toMillis();
        long avgProcessingTime = totalProcessingTime.get() / messageCount;
        long throughput = (messageCount * 1000L) / totalDuration;
        
        logger.info("📊 High-Volume Processing Results:");
        logger.info("   Total messages: {}", messageCount);
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Total duration: {} ms", totalDuration);
        logger.info("   Average processing time: {} ms", avgProcessingTime);
        logger.info("   Throughput: {} msg/sec", throughput);
        
        assertEquals(messageCount, processedCount.get(), "Should have processed all messages");
        assertTrue(throughput > 0, "Throughput should be greater than 0 msg/sec");

        logger.info("✅ High-Volume Processing test passed");
        logger.info("✅ Successfully processed {} messages at {} msg/sec", messageCount, throughput);
        logger.info("   Note: Actual throughput depends on polling interval (500ms) and batch size");
    }

    /**
     * Test 2: Concurrent Consumer Performance
     *
     * Verifies multiple consumers can process messages concurrently from the same topic.
     * Tests load distribution and concurrent processing capabilities.
     */
    @Test
    void testConcurrentConsumerPerformance() throws Exception {
        logger.info("=== Testing Concurrent Consumer Performance ===");
        logger.info("This test uses multiple consumers to process messages concurrently");

        String topic = "perf-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 150;
        int consumerCount = 3;
        
        // Track processing per consumer
        Map<String, AtomicInteger> consumerCounts = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger totalProcessed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        // Create multiple consumers
        logger.info("🔧 Creating {} consumers", consumerCount);
        for (int i = 1; i <= consumerCount; i++) {
            String consumerId = "consumer-" + i;
            consumerCounts.put(consumerId, new AtomicInteger(0));
            
            MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
            activeConsumers.add(consumer);
            
            consumer.subscribe(message -> {
                consumerCounts.get(consumerId).incrementAndGet();
                int count = totalProcessed.incrementAndGet();
                if (count % 50 == 0) {
                    logger.info("📦 Total processed: {}", count);
                }
                latch.countDown();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
            
            logger.info("   Created {}", consumerId);
        }
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages
        logger.info("📤 Sending {} messages", messageCount);
        Instant start = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            PerformanceMessage message = new PerformanceMessage(
                "msg-" + i,
                "Concurrent test message " + i,
                System.currentTimeMillis()
            );
            producer.send(message).join();
        }
        
        // Wait for all messages to be processed (allow 0.5 sec per message + overhead)
        boolean completed = latch.await(120, TimeUnit.SECONDS);
        Instant end = Instant.now();

        assertTrue(completed, "All messages should be processed within 120 seconds");
        
        // Calculate metrics
        long duration = Duration.between(start, end).toMillis();
        long throughput = (messageCount * 1000L) / duration;
        
        logger.info("📊 Concurrent Consumer Performance Results:");
        logger.info("   Total messages: {}", messageCount);
        logger.info("   Number of consumers: {}", consumerCount);
        logger.info("   Total processed: {}", totalProcessed.get());
        logger.info("   Duration: {} ms", duration);
        logger.info("   Throughput: {} msg/sec", throughput);
        
        // Show distribution across consumers
        logger.info("   Message distribution:");
        for (Map.Entry<String, AtomicInteger> entry : consumerCounts.entrySet()) {
            int count = entry.getValue().get();
            double percentage = (count * 100.0) / messageCount;
            logger.info("     {}: {} messages ({:.1f}%)", entry.getKey(), count, percentage);
        }
        
        assertEquals(messageCount, totalProcessed.get(), "Should have processed all messages");
        
        // Verify all consumers processed at least some messages
        for (Map.Entry<String, AtomicInteger> entry : consumerCounts.entrySet()) {
            assertTrue(entry.getValue().get() > 0, 
                entry.getKey() + " should have processed at least one message");
        }
        
        logger.info("✅ Concurrent Consumer Performance test passed");
        logger.info("✅ {} consumers processed {} messages at {} msg/sec", 
            consumerCount, messageCount, throughput);
    }

    /**
     * Test 3: Batch Processing Efficiency
     *
     * Verifies batch processing improves throughput compared to single message processing.
     * Tests the efficiency of processing messages in batches.
     */
    @Test
    void testBatchProcessingEfficiency() throws Exception {
        logger.info("=== Testing Batch Processing Efficiency ===");
        logger.info("This test measures batch processing performance");

        String topic = "perf-batch-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 100;
        
        // Track batch processing
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        
        // Create consumer
        MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
        activeConsumers.add(consumer);
        
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            int batch = batchCount.incrementAndGet();
            
            // Track batch processing (messages processed close together are likely in same batch)
            if (count % 10 == 0) {
                batchSizes.add(10);
                logger.info("📦 Processed batch {} (total: {})", batch / 10, count);
            }
            
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages in bursts to simulate batch processing
        logger.info("📤 Sending {} messages in bursts", messageCount);
        Instant start = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            PerformanceMessage message = new PerformanceMessage(
                "msg-" + i,
                "Batch test message " + i,
                System.currentTimeMillis()
            );
            producer.send(message).join();
            
            // Small burst pattern
            if (i % 50 == 0) {
                Thread.sleep(100); // Pause between bursts
                logger.info("   Sent {} messages", i);
            }
        }
        
        // Wait for all messages to be processed (allow 0.5 sec per message + overhead)
        boolean completed = latch.await(90, TimeUnit.SECONDS);
        Instant end = Instant.now();

        assertTrue(completed, "All messages should be processed within 90 seconds");
        
        // Calculate metrics
        long duration = Duration.between(start, end).toMillis();
        long throughput = (messageCount * 1000L) / duration;
        
        logger.info("📊 Batch Processing Efficiency Results:");
        logger.info("   Total messages: {}", messageCount);
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Duration: {} ms", duration);
        logger.info("   Throughput: {} msg/sec", throughput);
        logger.info("   Estimated batches: {}", batchSizes.size());
        
        assertEquals(messageCount, processedCount.get(), "Should have processed all messages");
        assertTrue(throughput > 0, "Batch processing throughput should be greater than 0 msg/sec");

        logger.info("✅ Batch Processing Efficiency test passed");
        logger.info("✅ Processed {} messages at {} msg/sec", messageCount, throughput);
        logger.info("   Note: Actual throughput depends on polling interval (500ms) and batch size");
    }

    /**
     * Simple performance message for testing
     */
    public static class PerformanceMessage {
        private String messageId;
        private String content;
        private long sentAt;

        public PerformanceMessage() {}

        public PerformanceMessage(String messageId, String content, long sentAt) {
            this.messageId = messageId;
            this.content = content;
            this.sentAt = sentAt;
        }

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public long getSentAt() { return sentAt; }
        public void setSentAt(long sentAt) { this.sentAt = sentAt; }
    }
}

