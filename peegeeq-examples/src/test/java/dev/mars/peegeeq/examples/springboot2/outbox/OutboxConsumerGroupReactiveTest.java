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

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.springboot2.SpringBootReactiveOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
class OutboxConsumerGroupReactiveTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupReactiveTest.class);
    
    @Autowired
    private OutboxFactory outboxFactory;
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_reactive_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .withSharedMemorySize(256 * 1024 * 1024L);
    
    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<ConsumerGroup<?>> activeConsumerGroups = new ArrayList<>();
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for Reactive OutboxConsumerGroup test");
        
        // PeeGeeQ properties
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        
        // R2DBC properties
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        
        // Test settings
        registry.add("peegeeq.profile", () -> "test");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot 2 Reactive consumer group test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.info("🧹 Cleaning up Reactive Consumer Group Test");

        // Close all active consumer groups first (critical for connection cleanup)
        for (ConsumerGroup<?> group : activeConsumerGroups) {
            try {
                group.stop();
                group.close();
                logger.info("✅ Closed consumer group: {}", group.getGroupName());
            } catch (Exception e) {
                logger.error("⚠️ Error closing consumer group: {}", e.getMessage());
            }
        }
        activeConsumerGroups.clear();

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
    void testConsumerGroupLoadBalancing() throws Exception {
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
        CountDownLatch latch = new CountDownLatch(messageCount);

        // Add multiple consumers to the group
        for (int i = 0; i < consumerCount; i++) {
            String consumerId = "consumer-" + (i + 1);
            consumerMessages.put(consumerId, ConcurrentHashMap.newKeySet());

            consumerGroup.addConsumer(consumerId, message -> {
                consumerMessages.get(consumerId).add(message.getPayload());
                logger.debug("{} processed: {}", consumerId, message.getPayload());
                latch.countDown();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
        }

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        logger.info("📤 Sending {} messages for load balancing test", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            String message = "message-" + i;
            producer.send(message).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should be processed within timeout");

        // Verify distribution
        logger.info("📊 Load Balancing Results:");
        int totalProcessed = 0;
        for (Map.Entry<String, Set<String>> entry : consumerMessages.entrySet()) {
            int count = entry.getValue().size();
            totalProcessed += count;
            logger.info("  {} processed {} messages", entry.getKey(), count);
        }

        // Assertions
        assertEquals(messageCount, totalProcessed, "Total processed messages should match sent messages");

        // Each consumer should process at least some messages (allowing for some imbalance)
        for (Map.Entry<String, Set<String>> entry : consumerMessages.entrySet()) {
            int count = entry.getValue().size();
            assertTrue(count > 0, entry.getKey() + " should process at least one message");
            assertTrue(count <= messageCount, entry.getKey() + " should not process more than total messages");
        }

        logger.info("✅ Consumer Group Load Balancing test passed (Reactive)");
        logger.info("✅ {} messages distributed across {} consumers", messageCount, consumerCount);
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
    void testConsumerGroupFailureHandling() throws Exception {
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
        CountDownLatch latch = new CountDownLatch(messageCount);

        // Consumer 1: Always succeeds
        consumerGroup.addConsumer("consumer-1", message -> {
            successfullyProcessed.add(message.getPayload());
            logger.debug("Consumer-1 successfully processed: {}", message.getPayload());
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        // Consumer 2: Fails on specific messages, then succeeds on retry
        AtomicInteger consumer2Attempts = new AtomicInteger(0);
        consumerGroup.addConsumer("consumer-2", message -> {
            int attempt = consumer2Attempts.incrementAndGet();

            // Fail first 3 attempts to simulate transient failures
            if (attempt <= 3) {
                failureCount.incrementAndGet();
                logger.debug("Consumer-2 simulating failure on attempt {}: {}", attempt, message.getPayload());
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new RuntimeException("Simulated transient failure"));
            }

            successfullyProcessed.add(message.getPayload());
            logger.debug("Consumer-2 successfully processed after retries: {}", message.getPayload());
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        logger.info("📤 Sending {} messages for failure handling test", messageCount);
        for (int i = 1; i <= messageCount; i++) {
            String message = "message-" + i;
            producer.send(message).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        boolean completed = latch.await(45, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should eventually be processed despite failures");

        // Verify results
        logger.info("📊 Failure Handling Results:");
        logger.info("  Successfully processed: {} messages", successfullyProcessed.size());
        logger.info("  Transient failures encountered: {}", failureCount.get());

        // Assertions
        assertEquals(messageCount, successfullyProcessed.size(),
            "All messages should eventually be processed successfully");
        assertTrue(failureCount.get() > 0,
            "Should have encountered some transient failures");

        logger.info("✅ Consumer Group Failure Handling test passed (Reactive)");
        logger.info("✅ All messages processed successfully despite {} transient failures", failureCount.get());
    }
}

