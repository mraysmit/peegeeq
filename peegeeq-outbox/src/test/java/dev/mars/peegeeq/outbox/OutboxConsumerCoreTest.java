package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for OutboxConsumer core functionality.
 * Tests consumer operations with real database using TestContainers.
 * 
 * Focuses on testing uncovered consumer methods to increase coverage from 75% to 90%+:
 * - Consumer lifecycle (subscribe, unsubscribe, close)
 * - Message receiving and handling
 * - Consumer group tracking
 * - Error handling during consumption
 * - Configuration-based behavior
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerCoreTest {

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== OutboxConsumerCoreTest SETUP STARTED ===");
        
        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test
        testTopic = "consumer-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("consumer-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        System.err.println("=== OutboxConsumerCoreTest SETUP COMPLETED ===");
    }

    @AfterEach
    void tearDown() throws Exception {
        System.err.println("=== OutboxConsumerCoreTest TEARDOWN STARTED ===");
        
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            manager.closeReactive().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
        }

        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        System.err.println("=== OutboxConsumerCoreTest TEARDOWN COMPLETED ===");
    }

    @Test
    void testConsumerCreation() {
        System.err.println("=== TEST: testConsumerCreation STARTED ===");
        
        assertNotNull(consumer, "Consumer should be created");
        
        System.err.println("=== TEST: testConsumerCreation COMPLETED ===");
    }

    @Test
    void testConsumerSubscribe(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerSubscribe STARTED ===");
        
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to receive messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        // Send a test message
        String testMessage = "Test message for subscribe";
        CountDownLatch sendLatch = new CountDownLatch(1);
        producer.send(testMessage).onComplete(ar -> sendLatch.countDown());
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send should complete");

        // Wait for message to be received
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        System.err.println("=== TEST: testConsumerSubscribe COMPLETED ===");
    }

    @Test
    void testConsumerUnsubscribe(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerUnsubscribe STARTED ===");
        
        CountDownLatch firstReceivedLatch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);

        // Subscribe
        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            firstReceivedLatch.countDown();
            return Future.succeededFuture();
        });

        // Send first message
        CountDownLatch sendLatch1 = new CountDownLatch(1);
        producer.send("Message 1").onComplete(ar -> sendLatch1.countDown());
        assertTrue(sendLatch1.await(5, TimeUnit.SECONDS), "Send should complete");
        assertTrue(firstReceivedLatch.await(10, TimeUnit.SECONDS), "Should receive first message");
        assertEquals(1, messageCount.get(), "Should have received one message");

        // Unsubscribe
        consumer.unsubscribe();
        
        // Wait for unsubscribe to take effect
        CountDownLatch unsubWait = new CountDownLatch(1);
        vertx.setTimer(1000, timerId -> unsubWait.countDown());
        assertTrue(unsubWait.await(5, TimeUnit.SECONDS), "Timer should complete");

        // Send second message - should not be received
        CountDownLatch sendLatch2 = new CountDownLatch(1);
        producer.send("Message 2").onComplete(ar -> sendLatch2.countDown());
        assertTrue(sendLatch2.await(5, TimeUnit.SECONDS), "Send should complete");
        CountDownLatch deliveryWait = new CountDownLatch(1);
        vertx.setTimer(2000, timerId -> deliveryWait.countDown());
        assertTrue(deliveryWait.await(5, TimeUnit.SECONDS), "Timer should complete");

        // Message count should still be 1
        assertEquals(1, messageCount.get(), "Should not receive messages after unsubscribe");
        
        System.err.println("=== TEST: testConsumerUnsubscribe COMPLETED ===");
        testContext.completeNow();
    }

    @Test
    void testConsumerReceivesMultipleMessages(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerReceivesMultipleMessages STARTED ===");
        
        int messageCount = 5;
        Checkpoint latch = testContext.checkpoint(messageCount);
        AtomicInteger receivedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            latch.flag();
            return Future.succeededFuture();
        });

        // Send multiple messages
        CountDownLatch sendLatch = new CountDownLatch(messageCount);
        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).onComplete(ar -> sendLatch.countDown());
        }
        assertTrue(sendLatch.await(10, TimeUnit.SECONDS), "All sends should complete");

        // Wait for all messages
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all messages within timeout");
        assertEquals(messageCount, receivedCount.get(), "Should receive all messages");
        
        System.err.println("=== TEST: testConsumerReceivesMultipleMessages COMPLETED ===");
    }

    @Test
    void testConsumerReceivesMessagesWithHeaders(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerReceivesMessagesWithHeaders STARTED ===");
        
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedHeaders.set(message.getHeaders());
            latch.flag();
            return Future.succeededFuture();
        });

        // Send message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "test");
        
        CountDownLatch sendLatch = new CountDownLatch(1);
        producer.send("Message with headers", headers).onComplete(ar -> sendLatch.countDown());
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send should complete");

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertNotNull(receivedHeaders.get(), "Should receive headers");
        assertTrue(receivedHeaders.get().containsKey("content-type"), "Should contain content-type header");
        assertEquals("application/json", receivedHeaders.get().get("content-type"), "Header value should match");
        
        System.err.println("=== TEST: testConsumerReceivesMessagesWithHeaders COMPLETED ===");
    }

    @Test
    void testConsumerHandlerException(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerHandlerException STARTED ===");
        
        Checkpoint latch = testContext.checkpoint();
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            latch.flag();
            
            // Simulate handler error on first attempt
            if (attempt == 1) {
                return Future.failedFuture(new RuntimeException("Handler error"));
            }
            return Future.succeededFuture();
        });

        // Send a message
        CountDownLatch sendLatch = new CountDownLatch(1);
        producer.send("Message that causes error").onComplete(ar -> sendLatch.countDown());
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send should complete");

        // Wait for at least one delivery attempt
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should attempt to process message");
        assertTrue(attemptCount.get() >= 1, "Should have at least one processing attempt");
        
        System.err.println("=== TEST: testConsumerHandlerException COMPLETED ===");
    }

    @Test
    void testConsumerClose(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerClose STARTED ===");
        
        CountDownLatch firstReceivedLatch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            firstReceivedLatch.countDown();
            return Future.succeededFuture();
        });

        // Send a message before closing
        CountDownLatch sendLatch1 = new CountDownLatch(1);
        producer.send("Message before close").onComplete(ar -> sendLatch1.countDown());
        assertTrue(sendLatch1.await(5, TimeUnit.SECONDS), "Send should complete");
        assertTrue(firstReceivedLatch.await(10, TimeUnit.SECONDS), "Should receive first message");

        // Close consumer
        consumer.close();

        // Try to send another message
        CountDownLatch sendLatch2 = new CountDownLatch(1);
        producer.send("Message after close").onComplete(ar -> sendLatch2.countDown());
        assertTrue(sendLatch2.await(5, TimeUnit.SECONDS), "Send should complete");
        
        // Wait to verify no additional delivery
        CountDownLatch closeWait = new CountDownLatch(1);
        vertx.setTimer(2000, timerId -> closeWait.countDown());
        assertTrue(closeWait.await(5, TimeUnit.SECONDS), "Timer should complete");

        assertEquals(1, messageCount.get(), "Should only have received one message");
        
        System.err.println("=== TEST: testConsumerClose COMPLETED ===");
        testContext.completeNow();
    }

    @Test
    void testConsumerGroupNameSetting(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        System.err.println("=== TEST: testConsumerGroupNameSetting STARTED ===");
        
        // Create a new consumer with consumer group
        MessageConsumer<String> groupConsumer = outboxFactory.createConsumer(testTopic, String.class);
        
        // Set consumer group name
        if (groupConsumer instanceof OutboxConsumer) {
            ((OutboxConsumer<String>) groupConsumer).setConsumerGroupName("test-group");
        }

        Checkpoint latch = testContext.checkpoint();
        
        groupConsumer.subscribe(message -> {
            latch.flag();
            return Future.succeededFuture();
        });

        // Send a message
        CountDownLatch sendLatch = new CountDownLatch(1);
        producer.send("Message for consumer group").onComplete(ar -> sendLatch.countDown());
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send should complete");

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer with group should receive message");
        
        groupConsumer.close();
        
        System.err.println("=== TEST: testConsumerGroupNameSetting COMPLETED ===");
    }
}


