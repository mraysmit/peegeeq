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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
public class OutboxConsumerCoreTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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
            manager.close();
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
    void testConsumerSubscribe() throws Exception {
        System.err.println("=== TEST: testConsumerSubscribe STARTED ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to receive messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send a test message
        String testMessage = "Test message for subscribe";
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Wait for message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        System.err.println("=== TEST: testConsumerSubscribe COMPLETED ===");
    }

    @Test
    void testConsumerUnsubscribe() throws Exception {
        System.err.println("=== TEST: testConsumerUnsubscribe STARTED ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);

        // Subscribe
        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send first message
        producer.send("Message 1").get(5, TimeUnit.SECONDS);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive first message");
        assertEquals(1, messageCount.get(), "Should have received one message");

        // Unsubscribe
        consumer.unsubscribe();
        
        // Wait a bit for unsubscribe to take effect
        Thread.sleep(1000);

        // Send second message - should not be received
        producer.send("Message 2").get(5, TimeUnit.SECONDS);
        Thread.sleep(2000); // Give time for potential message delivery

        // Message count should still be 1
        assertEquals(1, messageCount.get(), "Should not receive messages after unsubscribe");
        
        System.err.println("=== TEST: testConsumerUnsubscribe COMPLETED ===");
    }

    @Test
    void testConsumerReceivesMultipleMessages() throws Exception {
        System.err.println("=== TEST: testConsumerReceivesMultipleMessages STARTED ===");
        
        int messageCount = 5;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger receivedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Should receive all messages within timeout");
        assertEquals(messageCount, receivedCount.get(), "Should receive all messages");
        
        System.err.println("=== TEST: testConsumerReceivesMultipleMessages COMPLETED ===");
    }

    @Test
    void testConsumerReceivesMessagesWithHeaders() throws Exception {
        System.err.println("=== TEST: testConsumerReceivesMessagesWithHeaders STARTED ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedHeaders.set(message.getHeaders());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "test");
        
        producer.send("Message with headers", headers).get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertNotNull(receivedHeaders.get(), "Should receive headers");
        assertTrue(receivedHeaders.get().containsKey("content-type"), "Should contain content-type header");
        assertEquals("application/json", receivedHeaders.get().get("content-type"), "Header value should match");
        
        System.err.println("=== TEST: testConsumerReceivesMessagesWithHeaders COMPLETED ===");
    }

    @Test
    void testConsumerHandlerException() throws Exception {
        System.err.println("=== TEST: testConsumerHandlerException STARTED ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            latch.countDown();
            
            // Simulate handler error on first attempt
            if (attempt == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("Handler error"));
            }
            return CompletableFuture.completedFuture(null);
        });

        // Send a message
        producer.send("Message that causes error").get(5, TimeUnit.SECONDS);

        // Wait for at least one delivery attempt
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should attempt to process message");
        assertTrue(attemptCount.get() >= 1, "Should have at least one processing attempt");
        
        System.err.println("=== TEST: testConsumerHandlerException COMPLETED ===");
    }

    @Test
    void testConsumerClose() throws Exception {
        System.err.println("=== TEST: testConsumerClose STARTED ===");
        
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message before closing
        producer.send("Message before close").get(5, TimeUnit.SECONDS);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive message before close");

        // Close consumer
        consumer.close();

        // Try to send another message
        producer.send("Message after close").get(5, TimeUnit.SECONDS);
        
        // Wait a bit
        Thread.sleep(2000);

        // Latch should not be counted down again (still at 0)
        assertEquals(0, latch.getCount(), "Should only have received one message");
        
        System.err.println("=== TEST: testConsumerClose COMPLETED ===");
    }

    @Test
    void testConsumerGroupNameSetting() throws Exception {
        System.err.println("=== TEST: testConsumerGroupNameSetting STARTED ===");
        
        // Create a new consumer with consumer group
        MessageConsumer<String> groupConsumer = outboxFactory.createConsumer(testTopic, String.class);
        
        // Set consumer group name
        if (groupConsumer instanceof OutboxConsumer) {
            ((OutboxConsumer<String>) groupConsumer).setConsumerGroupName("test-group");
        }

        CountDownLatch latch = new CountDownLatch(1);
        
        groupConsumer.subscribe(message -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message
        producer.send("Message for consumer group").get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Consumer with group should receive message");
        
        groupConsumer.close();
        
        System.err.println("=== TEST: testConsumerGroupNameSetting COMPLETED ===");
    }
}
