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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Basic integration tests for outbox producer and consumer functionality.
 * Tests fundamental message sending and receiving capabilities.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxBasicTest {

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
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("basic-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
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
    }

    @Test
    void testBasicMessageProducerAndConsumer() throws Exception {
        String testMessage = "Hello, Basic Outbox Test!";
        
        // Set up consumer first
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message
        CompletableFuture<Void> sendFuture = producer.send(testMessage);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Wait for message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedCount.get(), "Should receive exactly one message");
        assertEquals(testMessage, receivedMessages.get(0), "Should receive the correct message");
    }

    @Test
    void testMessageWithHeaders() throws Exception {
        String testMessage = "Message with headers test";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "source", "basic-test",
            "version", "1.0"
        );

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send message with headers
        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Wait for message and verify
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");
        
        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload(), "Should receive correct payload");
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"), "Should preserve content-type header");
        assertEquals("basic-test", receivedMessage.getHeaders().get("source"), "Should preserve source header");
        assertEquals("1.0", receivedMessage.getHeaders().get("version"), "Should preserve version header");
    }

    @Test
    void testMultipleMessages() throws Exception {
        int messageCount = 5;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer first
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send multiple messages
        List<CompletableFuture<Void>> sendFutures = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            String message = "Basic Test Message " + i;
            sendFutures.add(producer.send(message));
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Wait for all messages to be received
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be received within timeout");
        assertEquals(messageCount, receivedMessages.size(), "Should receive all sent messages");
    }

    @Test
    void testCorrelationId() throws Exception {
        String testMessage = "Correlation ID test";
        String correlationId = "test-correlation-" + UUID.randomUUID();

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send message with correlation ID
        CompletableFuture<Void> sendFuture = producer.send(testMessage, Map.of(), correlationId);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Wait for message and verify correlation ID
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");
        
        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload(), "Should receive correct payload");

        // Check correlation ID if the message is a SimpleMessage
        if (receivedMessage instanceof dev.mars.peegeeq.api.messaging.SimpleMessage) {
            dev.mars.peegeeq.api.messaging.SimpleMessage<String> simpleMessage =
                (dev.mars.peegeeq.api.messaging.SimpleMessage<String>) receivedMessage;
            assertEquals(correlationId, simpleMessage.getCorrelationId(), "Should preserve correlation ID");
        }
    }

    @Test
    void testMessageGroup() throws Exception {
        String testMessage = "Message group test";
        String messageGroup = "test-group-" + UUID.randomUUID();

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send message with message group
        CompletableFuture<Void> sendFuture = producer.send(testMessage, Map.of(), null, messageGroup);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Wait for message
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");
        assertEquals(testMessage, receivedMessages.get(0), "Should receive correct message");
    }
}
