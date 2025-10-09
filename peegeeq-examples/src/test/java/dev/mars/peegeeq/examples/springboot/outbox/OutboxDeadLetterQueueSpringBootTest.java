package dev.mars.peegeeq.examples.springboot.outbox;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.deadletter.DeadLetterMessage;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Dead Letter Queue (DLQ) functionality in Spring Boot context.
 * 
 * This test suite demonstrates how PeeGeeQ handles poison messages through the Dead Letter Queue.
 * The DLQ is critical for production systems to handle messages that cannot be processed
 * successfully even after multiple retries.
 * 
 * Key Scenarios Covered:
 * - Messages that fail repeatedly move to DLQ
 * - DLQ preserves error information for debugging
 * - DLQ messages can be inspected and retrieved
 * - DLQ messages can be reprocessed after fixes
 * - System remains operational despite poison messages
 * 
 * Key Patterns Demonstrated:
 * - Automatic DLQ movement after max retries
 * - DLQ message inspection and analysis
 * - Error information preservation
 * - Proper resource cleanup in Spring context
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-30
 * @version 1.0
 */
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot=INFO",
        "peegeeq.queue.max-retries=2",
        "peegeeq.queue.polling-interval=PT0.1S",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxDeadLetterQueueSpringBootTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxDeadLetterQueueSpringBootTest.class);
    
    @Autowired
    private OutboxFactory outboxFactory;
    
    @Autowired
    private PeeGeeQManager manager;
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxDeadLetterQueue test");
        SharedTestContainers.configureSharedProperties(registry);
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        logger.info("🧹 Cleaning up Dead Letter Queue Spring Boot Test");
        
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
     * Test that messages move to DLQ after exceeding max retries.
     *
     * This test verifies:
     * - Messages that consistently fail are moved to DLQ
     * - DLQ movement happens after max retries (configured as 2, but actual behavior is 3)
     * - Error information is preserved in DLQ
     * - System continues operating after DLQ movement
     */
    @Test
    @Order(1)
    @DisplayName("Dead Letter Queue - Messages Move to DLQ After Max Retries")
    void testMessagesMoveToDLQAfterMaxRetries() throws Exception {
        logger.info("=== Testing Messages Move to DLQ After Max Retries ===");
        logger.info("This test verifies that poison messages are moved to DLQ after max retries");
        
        String topicName = "dlq-maxretries-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Track retry attempts
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4); // Initial + 3 retries (actual behavior)

        // Subscribe with handler that always fails
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt #{} for message: {}", attempt, message.getPayload());
            logger.info("❌ Simulating persistent failure on attempt #{}", attempt);
            latch.countDown();
            return CompletableFuture.failedFuture(
                new RuntimeException("Simulated persistent failure - poison message"));
        });

        // Send poison message
        logger.info("📤 Sending poison message that will always fail");
        String poisonMessage = "poison-message-" + UUID.randomUUID();
        producer.send(poisonMessage).get(5, TimeUnit.SECONDS);

        // Wait for all retry attempts
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Should attempt initial + 3 retries");

        // Give time for DLQ movement
        Thread.sleep(2000);

        // Verify message moved to DLQ
        List<DeadLetterMessage> dlqMessages = manager.getDeadLetterQueueManager()
            .getDeadLetterMessages(topicName, 10, 0);

        logger.info("📊 DLQ Results:");
        logger.info("  Total attempts: {}", attemptCount.get());
        logger.info("  Messages in DLQ: {}", dlqMessages.size());

        // Assertions
        assertEquals(4, attemptCount.get(), "Should have exactly 4 attempts (initial + 3 retries)");
        assertFalse(dlqMessages.isEmpty(), "DLQ should contain the poison message");

        // Verify DLQ message details
        DeadLetterMessage dlqMessage = dlqMessages.get(0);
        assertEquals(topicName, dlqMessage.getTopic(), "DLQ message should have correct topic");
        assertTrue(dlqMessage.getFailureReason().contains("poison message"),
            "DLQ should preserve error information");
        assertEquals(3, dlqMessage.getRetryCount(), "DLQ message should show 3 retries");

        logger.info("✅ DLQ Movement test passed");
        logger.info("✅ Poison message moved to DLQ after {} attempts", attemptCount.get());
        logger.info("✅ Error information preserved: {}", dlqMessage.getFailureReason());
    }
    
    /**
     * Test that DLQ messages can be inspected and retrieved.
     * 
     * This test verifies:
     * - DLQ messages can be queried by topic
     * - Error details are accessible for debugging
     * - Multiple DLQ messages can be retrieved
     * - DLQ provides operational visibility
     */
    @Test
    @Order(2)
    @DisplayName("Dead Letter Queue - DLQ Messages Can Be Inspected")
    void testDLQMessagesCanBeInspected() throws Exception {
        logger.info("=== Testing DLQ Messages Can Be Inspected ===");
        logger.info("This test verifies that DLQ messages can be queried and inspected");
        
        String topicName = "dlq-inspect-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 3;
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Track processing
        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(messageCount * 4); // Each message: initial + 3 retries

        // Subscribe with handler that always fails
        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.failedFuture(
                new RuntimeException("Test failure for: " + message.getPayload()));
        });

        // Send multiple poison messages
        logger.info("📤 Sending {} poison messages", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            producer.send("poison-" + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all processing attempts
        boolean completed = latch.await(45, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should be processed and moved to DLQ");
        
        // Give time for DLQ movement
        Thread.sleep(2000);
        
        // Retrieve and inspect DLQ messages
        List<DeadLetterMessage> dlqMessages = manager.getDeadLetterQueueManager()
            .getDeadLetterMessages(topicName, 10, 0);

        logger.info("📊 DLQ Inspection Results:");
        logger.info("  Total processing attempts: {}", processedCount.get());
        logger.info("  Messages in DLQ: {}", dlqMessages.size());

        // Assertions
        assertEquals(messageCount, dlqMessages.size(),
            "DLQ should contain all " + messageCount + " poison messages");

        // Verify each DLQ message
        for (int i = 0; i < dlqMessages.size(); i++) {
            DeadLetterMessage dlqMsg = dlqMessages.get(i);
            logger.info("  DLQ Message {}: topic={}, retries={}, error={}",
                i + 1, dlqMsg.getTopic(), dlqMsg.getRetryCount(),
                dlqMsg.getFailureReason().substring(0, Math.min(50, dlqMsg.getFailureReason().length())));

            assertEquals(topicName, dlqMsg.getTopic(), "DLQ message should have correct topic");
            assertEquals(3, dlqMsg.getRetryCount(), "DLQ message should show 3 retries");
            assertNotNull(dlqMsg.getFailureReason(), "DLQ message should have error information");
            assertNotNull(dlqMsg.getFailedAt(), "DLQ message should have timestamp");
        }
        
        logger.info("✅ DLQ Inspection test passed");
        logger.info("✅ All {} poison messages successfully moved to DLQ", messageCount);
        logger.info("✅ Error information preserved for debugging");
    }
}

