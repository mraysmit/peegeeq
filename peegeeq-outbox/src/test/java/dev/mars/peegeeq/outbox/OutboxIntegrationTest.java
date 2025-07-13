package dev.mars.peegeeq.outbox;

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


import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the outbox pattern implementation.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
class OutboxIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("outbox_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() {
        // Configure test properties
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT1S");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Set system properties
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Initialize outbox components
        OutboxFactory outboxFactory = new OutboxFactory(manager.getClientFactory());
        producer = outboxFactory.createProducer("test-topic", String.class);
        consumer = outboxFactory.createConsumer("test-topic", String.class);
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (manager != null) {
            manager.close();
        }

        // Clean up system properties
        System.getProperties().entrySet().removeIf(entry -> 
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @Test
    void testBasicMessageProducerAndConsumer() throws Exception {
        String testMessage = "Hello, Outbox!";
        
        // Send a message
        CompletableFuture<Void> sendFuture = producer.send(testMessage);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Consume the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, receivedCount.get());
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testMessageWithHeaders() throws Exception {
        String testMessage = "Message with headers";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "source", "test",
            "version", "1.0"
        );

        // Send message with headers
        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Consume and verify headers
        CountDownLatch latch = new CountDownLatch(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, receivedMessages.size());

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload());
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"));
        assertEquals("test", receivedMessage.getHeaders().get("source"));
        assertEquals("1.0", receivedMessage.getHeaders().get("version"));
    }

    @Test
    void testMultipleMessages() throws Exception {
        int messageCount = 10;
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
            String message = "Message " + i;
            sendFutures.add(producer.send(message));
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Wait for all messages to be received
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(messageCount, receivedMessages.size());
    }

    @Test
    void testMessageProcessingFailureAndRetry() throws Exception {
        String testMessage = "Message that will fail initially";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                // Fail the first 2 attempts
                return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        // Wait for successful processing
        assertTrue(successLatch.await(20, TimeUnit.SECONDS));
        assertTrue(attemptCount.get() >= 3);
    }

    @Test
    void testConcurrentProducers() throws Exception {
        int producerCount = 5;
        int messagesPerProducer = 10;
        int totalMessages = producerCount * messagesPerProducer;

        CountDownLatch latch = new CountDownLatch(totalMessages);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Create multiple producers sending concurrently
        List<CompletableFuture<Void>> allSends = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            for (int m = 0; m < messagesPerProducer; m++) {
                final int messageId = m;
                String message = "Producer-" + producerId + "-Message-" + messageId;
                allSends.add(producer.send(message));
            }
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(allSends.toArray(new CompletableFuture[0]))
            .get(15, TimeUnit.SECONDS);

        // Wait for all messages to be received
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(totalMessages, receivedMessages.size());
    }

    @Test
    void testMessageOrdering() throws Exception {
        int messageCount = 5;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages in order
        for (int i = 0; i < messageCount; i++) {
            String message = "Ordered-Message-" + i;
            producer.send(message).get(1, TimeUnit.SECONDS);
        }

        // Wait for all messages to be received
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(messageCount, receivedMessages.size());

        // Verify order (messages should be received in the order they were sent)
        for (int i = 0; i < messageCount; i++) {
            assertEquals("Ordered-Message-" + i, receivedMessages.get(i));
        }
    }

    @Test
    void testConsumerUnsubscribe() throws Exception {
        String testMessage = "Message before unsubscribe";
        AtomicInteger receivedCount = new AtomicInteger(0);

        // Subscribe and receive one message
        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Wait a bit for processing
        Thread.sleep(2000);
        assertEquals(1, receivedCount.get());

        // Unsubscribe
        consumer.unsubscribe();

        // Send another message
        producer.send("Message after unsubscribe").get(5, TimeUnit.SECONDS);

        // Wait and verify no additional messages were received
        Thread.sleep(3000);
        assertEquals(1, receivedCount.get());
    }

    @Test
    void testProducerClose() throws Exception {
        String testMessage = "Message before close";
        
        // Send a message successfully
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Close the producer
        producer.close();

        // Attempting to send after close should fail
        assertThrows(Exception.class, () -> {
            producer.send("Message after close").get(5, TimeUnit.SECONDS);
        });
    }

    @Test
    void testConsumerClose() throws Exception {
        AtomicInteger receivedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        // Send and receive a message
        producer.send("Message before close").get(5, TimeUnit.SECONDS);
        Thread.sleep(2000);
        assertEquals(1, receivedCount.get());

        // Close the consumer
        consumer.close();

        // Send another message
        producer.send("Message after close").get(5, TimeUnit.SECONDS);

        // Wait and verify no additional messages were received
        Thread.sleep(3000);
        assertEquals(1, receivedCount.get());
    }

    @Test
    void testMetricsIntegration() throws Exception {
        String testMessage = "Metrics test message";

        // Send a message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        consumer.subscribe(message -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for processing
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Verify metrics were recorded
        var metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesSent() > 0);
        assertTrue(metrics.getMessagesReceived() > 0);
        assertTrue(metrics.getMessagesProcessed() > 0);
    }

    @Test
    void testHealthCheckIntegration() throws Exception {
        // Verify system is healthy
        assertTrue(manager.isHealthy());

        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertTrue(healthStatus.isHealthy());
        assertTrue(healthStatus.getComponents().containsKey("outbox-queue"));
    }

    @Test
    void testCircuitBreakerIntegration() throws Exception {
        // This test verifies that circuit breaker protection is applied to outbox operations
        String testMessage = "Circuit breaker test";

        // Normal operation should work
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Verify circuit breaker metrics exist
        var cbManager = manager.getCircuitBreakerManager();
        assertNotNull(cbManager);
        
        // Circuit breakers should be created for database operations
        assertFalse(cbManager.getCircuitBreakerNames().isEmpty());
    }
}
