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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
@Tag(TestCategories.INTEGRATION)
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
@ExtendWith(VertxExtension.class)
class OutboxDeadLetterQueueSpringBootTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxDeadLetterQueueSpringBootTest.class);
    
    @Autowired
    private OutboxFactory outboxFactory;
    
    @Autowired
    private PeeGeeQManager manager;
    private static PeeGeeQManager managerRef;
    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxDeadLetterQueue test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot dead letter queue test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info(" Cleaning up Dead Letter Queue Spring Boot Test");

        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
                logger.info("Closed consumer");
            } catch (Exception e) {
                logger.error(" Error closing consumer: {}", e.getMessage());
            }
        }
        activeConsumers.clear();

        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
                logger.info("Closed producer");
            } catch (Exception e) {
                logger.error(" Error closing producer: {}", e.getMessage());
            }
        }
        activeProducers.clear();

        managerRef = manager;

        // Wait for connections to be fully released before next test
        logger.info(" Waiting for connections to be released...");
        vertx.timer(2000).onComplete(testContext.succeeding(v -> {
            logger.info("Cleanup complete");
            testContext.completeNow();
        }));
    }

    @AfterAll
    static void closeManager(VertxTestContext testContext) {
        if (managerRef == null) {
            testContext.completeNow();
            return;
        }
        managerRef.closeReactive().onComplete(testContext.succeedingThenComplete());
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
    @org.junit.jupiter.api.Timeout(60)
    void testMessagesMoveToDLQAfterMaxRetries(Vertx vertx, VertxTestContext testContext) {
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
        io.vertx.core.Promise<Void> retriesDone = io.vertx.core.Promise.promise();

        // Subscribe with handler that always fails; signal when initial + 2 retries are exhausted.
        // max-retries=2 means 3 total attempts (1 initial + 2 retries), not 4.
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt #{} for message: {}", attempt, message.getPayload());
            logger.info(" Simulating persistent failure on attempt #{}", attempt);
            if (attempt >= 3) {
                retriesDone.tryComplete();
            }
            return Future.failedFuture(
                new RuntimeException("Simulated persistent failure - poison message"));
        });

        // Send poison message
        logger.info(" Sending poison message that will always fail");
        String poisonMessage = "poison-message-" + UUID.randomUUID();
        producer.send(poisonMessage).onFailure(testContext::failNow);

        retriesDone.future()
            .compose(v -> vertx.timer(2000))
            .compose(v -> manager.getDeadLetterQueueManager()
                .getDeadLetterMessages(topicName, 10, 0))
            .onComplete(testContext.succeeding(dlqMessages -> testContext.verify(() -> {
                logger.info(" DLQ Results:");
                logger.info("  Total attempts: {}", attemptCount.get());
                logger.info("  Messages in DLQ: {}", dlqMessages.size());

                assertEquals(3, attemptCount.get(), "Should have exactly 3 attempts (initial + 2 retries, max-retries=2)");
                assertFalse(dlqMessages.isEmpty(), "DLQ should contain the poison message");

                DeadLetterMessageInfo dlqMessage = dlqMessages.get(0);
                assertEquals(topicName, dlqMessage.topic(), "DLQ message should have correct topic");
                assertTrue(dlqMessage.failureReason().contains("poison message"),
                    "DLQ should preserve error information");
                assertEquals(2, dlqMessage.retryCount(), "DLQ message should show 2 retries (max-retries=2)");

                logger.info("DLQ Movement test passed");
                logger.info("Poison message moved to DLQ after {} attempts", attemptCount.get());
                logger.info("Error information preserved: {}", dlqMessage.failureReason());
                testContext.completeNow();
            })));
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
    @org.junit.jupiter.api.Timeout(90)
    void testDLQMessagesCanBeInspected(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing DLQ Messages Can Be Inspected ===");
        logger.info("This test verifies that DLQ messages can be queried and inspected");

        String topicName = "dlq-inspect-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 3;

        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Track processing  each message: initial + 2 retries (max-retries=2  3 total attempts)
        AtomicInteger processedCount = new AtomicInteger(0);
        int expectedTotalAttempts = messageCount * 3;
        io.vertx.core.Promise<Void> allAttemptsDone = io.vertx.core.Promise.promise();

        // Subscribe with handler that always fails
        consumer.subscribe(message -> {
            int attempt = processedCount.incrementAndGet();
            if (attempt >= expectedTotalAttempts) {
                allAttemptsDone.tryComplete();
            }
            return Future.failedFuture(
                new RuntimeException("Test failure for: " + message.getPayload()));
        });

        // Send multiple poison messages
        logger.info(" Sending {} poison messages", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            producer.send("poison-" + i).onFailure(testContext::failNow);
        }

        allAttemptsDone.future()
            .compose(v -> vertx.timer(2000))
            .compose(v -> manager.getDeadLetterQueueManager()
                .getDeadLetterMessages(topicName, 10, 0))
            .onComplete(testContext.succeeding(dlqMessages -> testContext.verify(() -> {
                logger.info(" DLQ Inspection Results:");
                logger.info("  Total processing attempts: {}", processedCount.get());
                logger.info("  Messages in DLQ: {}", dlqMessages.size());

                assertEquals(messageCount, dlqMessages.size(),
                    "DLQ should contain all " + messageCount + " poison messages");

                for (int i = 0; i < dlqMessages.size(); i++) {
                    DeadLetterMessageInfo dlqMsg = dlqMessages.get(i);
                    logger.info("  DLQ Message {}: topic={}, retries={}, error={}",
                        i + 1, dlqMsg.topic(), dlqMsg.retryCount(),
                        dlqMsg.failureReason().substring(0, Math.min(50, dlqMsg.failureReason().length())));

                    assertEquals(topicName, dlqMsg.topic(), "DLQ message should have correct topic");
                    assertEquals(2, dlqMsg.retryCount(), "DLQ message should show 2 retries (max-retries=2)");
                    assertNotNull(dlqMsg.failureReason(), "DLQ message should have error information");
                    assertNotNull(dlqMsg.failedAt(), "DLQ message should have timestamp");
                }

                logger.info("DLQ Inspection test passed");
                logger.info("All {} poison messages successfully moved to DLQ", messageCount);
                logger.info("Error information preserved for debugging");
                testContext.completeNow();
            })));
    }
}

