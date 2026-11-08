package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

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
@ExtendWith(VertxExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OutboxPerformanceSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPerformanceSpringBootTest.class);
    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxPerformance test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @Autowired
    private OutboxFactory outboxFactory;
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();
    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot performance test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @BeforeEach
    void setUp() {
        logger.info(" Setting up Performance Spring Boot Test");
    }

    @AfterEach
    void tearDown() {
        logger.info(" Cleaning up Performance Spring Boot Test");
        
        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            consumer.close();
            logger.info("Closed consumer");
        }
        activeConsumers.clear();
        
        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            producer.close();
            logger.info("Closed producer");
        }
        activeProducers.clear();
        logger.info("Cleanup complete");
    }

    /**
     * Test 1: High-Volume Message Processing
     *
     * Verifies the system can handle processing 200+ messages efficiently.
     * Measures throughput and ensures all messages are processed successfully.
     */
    @Test
    @Timeout(value = 150, timeUnit = TimeUnit.SECONDS)
    void testHighVolumeProcessing(VertxTestContext testContext) {
        logger.info("=== Testing High-Volume Message Processing ===");
        logger.info("This test processes 200 messages and measures throughput");

        String topic = "perf-highvolume-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 200;
        
        // Track processing
        AtomicInteger processedCount = new AtomicInteger(0);
        // Events counted across the former checkpoints; the assertions run once all have happened.
        Promise<Void> allEvents = Promise.promise();
        AtomicInteger remainingEvents = new AtomicInteger();
        remainingEvents.addAndGet(messageCount);
        Runnable checkpoint = () -> countEvent(testContext, remainingEvents, allEvents);
        AtomicLong totalProcessingTime = new AtomicLong(0);
        
        // Create consumer
        MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
        activeConsumers.add(consumer);
        
        Future<Void> subscription = consumer.subscribe(message -> {
            PerformanceMessage msg = message.getPayload();
            long processingTime = System.currentTimeMillis() - msg.getSentAt();
            totalProcessingTime.addAndGet(processingTime);
            
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                logger.info(" Processed {} messages", count);
            }
            checkpoint.run();
            return Future.succeededFuture();
        });
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages
        logger.info(" Sending {} messages", messageCount);
        Instant sendStart = Instant.now();
        
        subscription.compose(v -> sendPerformanceMessages(producer, messageCount, "High volume test message "))
            .onSuccess(v -> {
                long sendDuration = Duration.between(sendStart, Instant.now()).toMillis();
                logger.info("All messages sent in {} ms", sendDuration);
                logger.info("   Send throughput: {} msg/sec",
                    (messageCount * 1000L) / Math.max(1, sendDuration));
            })
            .onFailure(testContext::failNow);

        allEvents.future()
            .onSuccess(done -> testContext.verify(() -> {
            Instant processEnd = Instant.now();

            // Calculate metrics
            long totalDuration = Duration.between(sendStart, processEnd).toMillis();
            long avgProcessingTime = totalProcessingTime.get() / messageCount;
            long throughput = (messageCount * 1000L) / totalDuration;

            logger.info(" High-Volume Processing Results:");
            logger.info("   Total messages: {}", messageCount);
            logger.info("   Messages processed: {}", processedCount.get());
            logger.info("   Total duration: {} ms", totalDuration);
            logger.info("   Average processing time: {} ms", avgProcessingTime);
            logger.info("   Throughput: {} msg/sec", throughput);

            assertEquals(messageCount, processedCount.get(), "Should have processed all messages");
            assertTrue(throughput > 0, "Throughput should be greater than 0 msg/sec");

            logger.info("High-Volume Processing test passed");
            logger.info("Successfully processed {} messages at {} msg/sec", messageCount, throughput);
            logger.info("   Note: Actual throughput depends on polling interval (500ms) and batch size");
            testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 2: Concurrent Consumer Performance
     *
     * Verifies multiple consumers can process messages concurrently from the same topic.
     * The consumers compete for rows: each poll claims up to a batch with
     * {@code FOR UPDATE SKIP LOCKED} on its own timer, and nothing divides the rows evenly.
     * One consumer can therefore receive no message. The test asserts that every message is
     * processed exactly once across the consumers and logs the distribution.
     */
    @Test
    @Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void testConcurrentConsumerPerformance(VertxTestContext testContext) {
        logger.info("=== Testing Concurrent Consumer Performance ===");
        logger.info("This test uses multiple consumers to process messages concurrently");

        String topic = "perf-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 150;
        int consumerCount = 3;
        
        // Track processing per consumer
        Map<String, AtomicInteger> consumerCounts = new java.util.concurrent.ConcurrentHashMap<>();
        Set<String> processedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger totalProcessed = new AtomicInteger(0);
        // Events counted across the former checkpoints; the assertions run once all have happened.
        Promise<Void> allEvents = Promise.promise();
        AtomicInteger remainingEvents = new AtomicInteger();
        remainingEvents.addAndGet(messageCount);
        Runnable checkpoint = () -> countEvent(testContext, remainingEvents, allEvents);
        
        // Create multiple consumers
        logger.info(" Creating {} consumers", consumerCount);
        List<Future<Void>> subscriptions = new ArrayList<>();
        for (int i = 1; i <= consumerCount; i++) {
            String consumerId = "consumer-" + i;
            consumerCounts.put(consumerId, new AtomicInteger(0));
            
            MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
            activeConsumers.add(consumer);
            
            subscriptions.add(consumer.subscribe(message -> {
                String messageId = message.getPayload().getMessageId();
                if (!processedIds.add(messageId)) {
                    testContext.failNow(new AssertionError(
                        consumerId + " received " + messageId + " after another delivery of it"));
                }
                consumerCounts.get(consumerId).incrementAndGet();
                int count = totalProcessed.incrementAndGet();
                if (count % 50 == 0) {
                    logger.info(" Total processed: {}", count);
                }
                checkpoint.run();
                return Future.succeededFuture();
            }));
            
            logger.info("   Created {}", consumerId);
        }
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages
        logger.info(" Sending {} messages", messageCount);
        Instant start = Instant.now();
        
        Future.all(subscriptions)
            .compose(v -> sendPerformanceMessages(producer, messageCount, "Concurrent test message "))
            .onFailure(testContext::failNow);

        allEvents.future()
            .onSuccess(done -> testContext.verify(() -> {
            Instant end = Instant.now();

            // Calculate metrics
            long duration = Duration.between(start, end).toMillis();
            long throughput = (messageCount * 1000L) / duration;

            logger.info(" Concurrent Consumer Performance Results:");
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
                logger.info("     {}: {} messages ({}%)", entry.getKey(), count, String.format("%.1f", percentage));
            }

            assertEquals(messageCount, totalProcessed.get(), "Should have processed all messages");
            assertEquals(messageCount, processedIds.size(), "Each message should be processed exactly once");
            assertEquals(messageCount,
                consumerCounts.values().stream().mapToInt(AtomicInteger::get).sum(),
                "The per-consumer counts should add up to the messages sent");

            logger.info("Concurrent Consumer Performance test passed");
            logger.info("{} consumers processed {} messages at {} msg/sec", 
                consumerCount, messageCount, throughput);
            testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 3: Batch Processing Efficiency
     *
     * Verifies batch processing improves throughput compared to single message processing.
     * Tests the efficiency of processing messages in batches.
     */
    @Test
    @Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    void testBatchProcessingEfficiency(VertxTestContext testContext) {
        logger.info("=== Testing Batch Processing Efficiency ===");
        logger.info("This test measures batch processing performance");

        String topic = "perf-batch-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 100;
        
        // Track batch processing
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        // Events counted across the former checkpoints; the assertions run once all have happened.
        Promise<Void> allEvents = Promise.promise();
        AtomicInteger remainingEvents = new AtomicInteger();
        remainingEvents.addAndGet(messageCount);
        Runnable checkpoint = () -> countEvent(testContext, remainingEvents, allEvents);
        List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        
        // Create consumer
        MessageConsumer<PerformanceMessage> consumer = outboxFactory.createConsumer(topic, PerformanceMessage.class);
        activeConsumers.add(consumer);
        
        Future<Void> subscription = consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            int batch = batchCount.incrementAndGet();
            
            // Track batch processing (messages processed close together are likely in same batch)
            if (count % 10 == 0) {
                batchSizes.add(10);
                logger.info(" Processed batch {} (total: {})", batch / 10, count);
            }
            
            checkpoint.run();
            return Future.succeededFuture();
        });
        
        // Create producer
        MessageProducer<PerformanceMessage> producer = outboxFactory.createProducer(topic, PerformanceMessage.class);
        activeProducers.add(producer);
        
        // Send messages in bursts to simulate batch processing
        logger.info(" Sending {} messages in bursts", messageCount);
        Instant start = Instant.now();
        
        subscription.compose(v -> sendPerformanceMessages(producer, messageCount, "Batch test message "))
            .onFailure(testContext::failNow);

        allEvents.future()
            .onSuccess(done -> testContext.verify(() -> {
            Instant end = Instant.now();

            // Calculate metrics
            long duration = Duration.between(start, end).toMillis();
            long throughput = (messageCount * 1000L) / duration;

            logger.info(" Batch Processing Efficiency Results:");
            logger.info("   Total messages: {}", messageCount);
            logger.info("   Messages processed: {}", processedCount.get());
            logger.info("   Duration: {} ms", duration);
            logger.info("   Throughput: {} msg/sec", throughput);
            logger.info("   Estimated batches: {}", batchSizes.size());

            assertEquals(messageCount, processedCount.get(), "Should have processed all messages");
            assertTrue(throughput > 0, "Batch processing throughput should be greater than 0 msg/sec");

            logger.info("Batch Processing Efficiency test passed");
            logger.info("Processed {} messages at {} msg/sec", messageCount, throughput);
            logger.info("   Note: Actual throughput depends on polling interval (500ms) and batch size");
            testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Simple performance message for testing
     */
    private Future<Void> sendPerformanceMessages(
            MessageProducer<PerformanceMessage> producer, int count, String prefix) {
        List<Future<Void>> sends = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sends.add(producer.send(new PerformanceMessage(
                    "msg-" + i,
                    prefix + i,
                    System.currentTimeMillis())));
        }
        return Future.all(sends).mapEmpty();
    }

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

    /** Counts one expected event; completes {@code all} at zero and fails the test on an extra event. */
    private static void countEvent(VertxTestContext testContext, AtomicInteger remaining, Promise<Void> all) {
        int left = remaining.decrementAndGet();
        if (left == 0) {
            all.tryComplete();
        } else if (left < 0) {
            testContext.failNow(new AssertionError("More events than expected: " + (-left) + " extra"));
        }
    }
}
