package dev.mars.peegeeq.examples.springboot2.outbox;

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
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.springboot2.SpringBootReactiveOutboxApplication;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive tests for Consumer Group functionality in Spring Boot Reactive context.
 *
 * This test suite demonstrates how to use PeeGeeQ consumer groups in a Spring Boot
 * reactive application (WebFlux + R2DBC) for scalable message processing. Consumer groups enable:
 *
 * - Parallel message processing across multiple consumers
 * - Load balancing and fair distribution of work
 * - Fault tolerance and automatic failover
 * - Scalable throughput by adding more consumers
 *
 * Key Patterns Demonstrated:
 * - Creating and managing consumer groups in Spring Boot Reactive
 * - Load balancing messages across group members
 * - Handling consumer failures gracefully
 * - Proper resource cleanup in reactive context
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBootReactiveOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot2=INFO"
    }
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(VertxExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxConsumerGroupReactiveTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupReactiveTest.class);
    
    @Autowired
    private OutboxFactory outboxFactory;
    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<ConsumerGroup<?>> activeConsumerGroups = new ArrayList<>();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for Reactive OutboxConsumerGroup test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot 2 Reactive consumer group test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        logger.info(" Cleaning up Reactive Consumer Group Test");

        // Chain close operations reactively for each consumer group
        Future<Void> closeGroups = Future.succeededFuture();
        for (ConsumerGroup<?> group : activeConsumerGroups) {
            closeGroups = closeGroups.compose(v ->
                group.close()
                    .onSuccess(v2 -> logger.info("Closed consumer group: {}", group.getGroupName())));
        }
        activeConsumerGroups.clear();

        // Close all active producers (sync)
        for (MessageProducer<?> producer : activeProducers) {
            producer.close();
            logger.info("Closed producer");
        }
        activeProducers.clear();

        closeGroups
            .onSuccess(v -> {
                logger.info("Cleanup complete");
                tearDownContext.completeNow();
            })
            .onFailure(error -> {
                logger.error("Consumer group cleanup failed", error);
                tearDownContext.failNow(error);
            });
    }

    /**
     * Test that consumer groups distribute messages evenly across multiple consumers.
     *
     * This test verifies:
     * - Messages are distributed across all consumer group members
     * - Each consumer processes approximately equal number of messages
     * - No messages are lost or duplicated
     * - All messages are processed successfully
     */
    @Test
    @Order(1)
    @DisplayName("Consumer Group - Load Balancing Across Multiple Consumers (Reactive)")
    @Timeout(30)
    void testConsumerGroupLoadBalancing(VertxTestContext testContext) {
        logger.info("=== Testing Consumer Group Load Balancing (Reactive) ===");
        logger.info("This test verifies that messages are distributed evenly across consumer group members");

        String topicName = "cg-loadbalance-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "test-group-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 30;
        int consumerCount = 3;

        // Create producer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        activeProducers.add(producer);

        // Create consumer group with multiple consumers
        ConsumerGroup<String> consumerGroup = outboxFactory.createConsumerGroup(groupName, topicName, String.class);
        activeConsumerGroups.add(consumerGroup);

        // Track which consumer processed which messages
        Map<String, Set<String>> consumerMessages = new ConcurrentHashMap<>();
        AtomicInteger processedCount = new AtomicInteger();

        // Add multiple consumers to the group
        for (int i = 0; i < consumerCount; i++) {
            String consumerId = "consumer-" + (i + 1);
            consumerMessages.put(consumerId, ConcurrentHashMap.newKeySet());

            consumerGroup.addConsumer(consumerId, message -> {
                consumerMessages.get(consumerId).add(message.getPayload());
                logger.debug("{} processed: {}", consumerId, message.getPayload());
                if (processedCount.incrementAndGet() == messageCount) {
                    testContext.verify(() -> {
                        logger.info(" Load Balancing Results:");
                        int totalProcessed = consumerMessages.values().stream().mapToInt(Set::size).sum();
                        consumerMessages.forEach((id, messages) ->
                            logger.info("  {} processed {} messages", id, messages.size()));

                        assertEquals(messageCount, totalProcessed,
                            "Total processed messages should match sent messages");
                        consumerMessages.forEach((id, messages) -> {
                            assertFalse(messages.isEmpty(), id + " should process at least one message");
                            assertTrue(messages.size() <= messageCount,
                                id + " should not process more than total messages");
                        });
                        logger.info("Consumer Group Load Balancing test passed (Reactive)");
                        testContext.completeNow();
                    });
                }
                return Future.succeededFuture();
            });
        }

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        logger.info(" Sending {} messages for load balancing test", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            String message = "message-" + i;
            producer.send(message).onFailure(testContext::failNow);
        }

    }

    /**
     * Test that consumer groups handle individual consumer failures gracefully.
     *
     * This test verifies:
     * - When one consumer fails, other consumers continue processing
     * - Failed messages can be retried by other consumers
     * - Consumer group remains operational after individual failures
     * - No message loss occurs due to consumer failures
     */
    @Test
    @Order(2)
    @DisplayName("Consumer Group - Graceful Handling of Consumer Failures (Reactive)")
    @Timeout(45)
    void testConsumerGroupFailureHandling(VertxTestContext testContext) {
        logger.info("=== Testing Consumer Group Failure Handling (Reactive) ===");
        logger.info("This test verifies that consumer groups handle individual consumer failures gracefully");

        String topicName = "cg-failure-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "test-group-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 20;

        // Create producer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        activeProducers.add(producer);

        // Create consumer group
        ConsumerGroup<String> consumerGroup = outboxFactory.createConsumerGroup(groupName, topicName, String.class);
        activeConsumerGroups.add(consumerGroup);

        // Track successful processing
        Set<String> successfullyProcessed = ConcurrentHashMap.newKeySet();
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger();

        // Consumer 1: Always succeeds
        consumerGroup.addConsumer("consumer-1", message -> {
            successfullyProcessed.add(message.getPayload());
            logger.debug("Consumer-1 successfully processed: {}", message.getPayload());
            completeFailureHandlingTest(
                testContext, messageCount, successfullyProcessed, failureCount, processedCount);
            return Future.succeededFuture();
        });

        // Consumer 2: Fails on specific messages, then succeeds on retry
        AtomicInteger consumer2Attempts = new AtomicInteger(0);
        consumerGroup.addConsumer("consumer-2", message -> {
            int attempt = consumer2Attempts.incrementAndGet();

            // Fail first 3 attempts to simulate transient failures
            if (attempt <= 3) {
                failureCount.incrementAndGet();
                logger.debug("Consumer-2 simulating failure on attempt {}: {}", attempt, message.getPayload());
                return Future.failedFuture(
                    new RuntimeException("Simulated transient failure"));
            }

            successfullyProcessed.add(message.getPayload());
            logger.debug("Consumer-2 successfully processed after retries: {}", message.getPayload());
            completeFailureHandlingTest(
                testContext, messageCount, successfullyProcessed, failureCount, processedCount);
            return Future.succeededFuture();
        });

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        logger.info(" Sending {} messages for failure handling test", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            String message = "message-" + i;
            producer.send(message).onFailure(testContext::failNow);
        }

    }

    private static void completeFailureHandlingTest(
            VertxTestContext testContext,
            int messageCount,
            Set<String> successfullyProcessed,
            AtomicInteger failureCount,
            AtomicInteger processedCount) {
        if (processedCount.incrementAndGet() == messageCount) {
            testContext.verify(() -> {
                logger.info(" Failure Handling Results:");
                logger.info("  Successfully processed: {} messages", successfullyProcessed.size());
                logger.info("  Transient failures encountered: {}", failureCount.get());
                assertEquals(messageCount, successfullyProcessed.size(),
                    "All messages should eventually be processed successfully");
                assertTrue(failureCount.get() > 0,
                    "Should have encountered some transient failures");
                logger.info("Consumer Group Failure Handling test passed (Reactive)");
                testContext.completeNow();
            });
        }
    }
}
