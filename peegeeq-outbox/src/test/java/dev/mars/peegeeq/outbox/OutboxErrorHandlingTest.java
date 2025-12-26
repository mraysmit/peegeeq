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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for error handling, retry mechanisms, and failure scenarios in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxErrorHandlingTest {

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
        testTopic = "error-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("error-test");
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
    void testMessageProcessingFailureAndRetry() throws Exception {
        String testMessage = "Message that will fail initially";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        // Send the message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            System.out.println("Processing attempt " + attempt + " for message: " + message.getPayload());
            
            if (attempt < 3) {
                // Fail the first 2 attempts
                System.out.println("INTENTIONAL FAILURE: Simulating processing failure on attempt " + attempt);
                return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                System.out.println("SUCCESS: Processing succeeded on attempt " + attempt);
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        // Wait for eventual success (should retry and eventually succeed)
        assertTrue(successLatch.await(30, TimeUnit.SECONDS), 
            "Message should eventually be processed successfully after retries");
        assertTrue(attemptCount.get() >= 3, 
            "Should have made at least 3 attempts (2 failures + 1 success)");
    }

    @Test
    void testConsumerExceptionHandling() throws Exception {
        String testMessage = "Message that causes exception";
        AtomicInteger exceptionCount = new AtomicInteger(0);
        CountDownLatch exceptionLatch = new CountDownLatch(1);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that always throws exception
        consumer.subscribe(message -> {
            int count = exceptionCount.incrementAndGet();
            System.out.println("INTENTIONAL FAILURE: Processing attempt " + count + ", throwing exception");
            exceptionLatch.countDown();
            throw new RuntimeException("Intentional exception for testing");
        });

        // Wait for at least one exception to be thrown
        assertTrue(exceptionLatch.await(15, TimeUnit.SECONDS), 
            "Consumer should throw exception when processing message");
        assertTrue(exceptionCount.get() >= 1, 
            "Should have thrown at least one exception");
    }

    @Test
    void testProducerWithClosedConnection() throws Exception {
        System.out.println("🔌 ===== RUNNING INTENTIONAL CLOSED CONNECTION TEST =====");
        System.out.println("🔌 **INTENTIONAL TEST** - This test deliberately closes the producer and attempts to send a message");
        System.out.println("🔌 **INTENTIONAL TEST FAILURE** - Expected exception when sending with closed producer");

        String testMessage = "Message after close";

        // Close the producer
        producer.close();

        // Try to send message with closed producer
        CompletableFuture<Void> sendFuture = producer.send(testMessage);

        // Should complete exceptionally
        assertThrows(Exception.class, () -> {
            sendFuture.get(5, TimeUnit.SECONDS);
        }, "Sending with closed producer should throw exception");

        System.out.println("🔌 **SUCCESS** - Closed producer properly threw exception");
        System.out.println("🔌 ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testConsumerUnsubscribe() throws Exception {
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch firstMessageLatch = new CountDownLatch(1);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = receivedCount.incrementAndGet();
            System.out.println("Received message " + count + ": " + message.getPayload());
            firstMessageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send and receive first message
        producer.send("First message").get(5, TimeUnit.SECONDS);
        assertTrue(firstMessageLatch.await(10, TimeUnit.SECONDS), 
            "Should receive first message");
        assertEquals(1, receivedCount.get(), "Should have received exactly one message");

        // Unsubscribe
        consumer.unsubscribe();

        // Send another message
        producer.send("Second message after unsubscribe").get(5, TimeUnit.SECONDS);

        // Wait and verify no additional messages were received
        Thread.sleep(3000);
        assertEquals(1, receivedCount.get(), 
            "Should not receive messages after unsubscribe");
    }

    @Test
    void testConsumerClose() throws Exception {
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch firstMessageLatch = new CountDownLatch(1);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = receivedCount.incrementAndGet();
            System.out.println("Received message " + count + ": " + message.getPayload());
            firstMessageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send and receive first message
        producer.send("Message before close").get(5, TimeUnit.SECONDS);
        assertTrue(firstMessageLatch.await(10, TimeUnit.SECONDS), 
            "Should receive first message");
        assertEquals(1, receivedCount.get(), "Should have received exactly one message");

        // Close the consumer
        consumer.close();

        // Send another message
        producer.send("Message after close").get(5, TimeUnit.SECONDS);

        // Wait and verify no additional messages were received
        Thread.sleep(3000);
        assertEquals(1, receivedCount.get(), 
            "Should not receive messages after consumer is closed");
    }

    @Test
    void testNullMessageHandling() throws Exception {
        System.out.println("❌ ===== RUNNING INTENTIONAL NULL MESSAGE TEST =====");
        System.out.println("❌ **INTENTIONAL TEST** - This test deliberately sends a null payload");
        System.out.println("❌ **INTENTIONAL TEST FAILURE** - Expected exception when sending null payload");

        // Test sending null payload
        assertThrows(Exception.class, () -> {
            producer.send(null).get(5, TimeUnit.SECONDS);
        }, "Sending null payload should throw exception");

        System.out.println("❌ **SUCCESS** - Null payload properly threw exception");
        System.out.println("❌ ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testLargeMessageHandling() throws Exception {
        // Create a large message (1MB)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeMessage.append("This is a large message for testing purposes. ");
        }
        
        String testMessage = largeMessage.toString();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);

        // Set up consumer
        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send large message
        CompletableFuture<Void> sendFuture = producer.send(testMessage);
        sendFuture.get(10, TimeUnit.SECONDS);

        // Wait for message to be received
        assertTrue(latch.await(15, TimeUnit.SECONDS), 
            "Large message should be received within timeout");
        assertEquals(1, receivedCount.get(), 
            "Should receive exactly one large message");
    }
}
