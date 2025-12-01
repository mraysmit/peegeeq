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

import dev.mars.peegeeq.api.database.DatabaseService;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxProducer.
 * Tests producer functionality with real database using TestContainers.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxProducerCoreTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== OutboxProducerCoreTest SETUP STARTED ===");
        System.err.flush();

        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        // Use unique topic for each test to avoid interference
        testTopic = "test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("producer-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);

        System.err.println("=== OutboxProducerCoreTest SETUP COMPLETED ===");
        System.err.flush();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.err.println("=== OutboxProducerCoreTest TEARDOWN STARTED ===");
        System.err.flush();

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

        System.err.println("=== OutboxProducerCoreTest TEARDOWN COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testProducerCreation() {
        System.err.println("=== TEST: testProducerCreation STARTED ===");
        System.err.flush();

        assertNotNull(producer, "Producer should be created");

        System.err.println("=== TEST: testProducerCreation COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendBasicMessage() throws Exception {
        System.err.println("=== TEST: testSendBasicMessage STARTED ===");
        System.err.flush();

        String testMessage = "Hello, OutboxProducer Test!";

        CompletableFuture<Void> sendFuture = producer.send(testMessage);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.get(5, TimeUnit.SECONDS);

        System.err.println("=== TEST: testSendBasicMessage COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMessageWithHeaders() throws Exception {
        System.err.println("=== TEST: testSendMessageWithHeaders STARTED ===");
        System.err.flush();

        String testMessage = "Message with headers";
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        headers.put("source", "producer-test");

        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.get(5, TimeUnit.SECONDS);

        System.err.println("=== TEST: testSendMessageWithHeaders COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMessageWithCorrelationId() throws Exception {
        System.err.println("=== TEST: testSendMessageWithCorrelationId STARTED ===");
        System.err.flush();

        String testMessage = "Message with correlation ID";
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        String correlationId = UUID.randomUUID().toString();

        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers, correlationId);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.get(5, TimeUnit.SECONDS);

        System.err.println("=== TEST: testSendMessageWithCorrelationId COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMessageWithAllParameters() throws Exception {
        System.err.println("=== TEST: testSendMessageWithAllParameters STARTED ===");
        System.err.flush();

        String testMessage = "Message with all parameters";
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        String correlationId = UUID.randomUUID().toString();
        String messageGroup = "test-group";

        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers, correlationId, messageGroup);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.get(5, TimeUnit.SECONDS);

        System.err.println("=== TEST: testSendMessageWithAllParameters COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMultipleMessages() throws Exception {
        System.err.println("=== TEST: testSendMultipleMessages STARTED ===");
        System.err.flush();

        int messageCount = 10;
        CompletableFuture<?>[] futures = new CompletableFuture[messageCount];

        for (int i = 0; i < messageCount; i++) {
            String message = "Message " + i;
            futures[i] = producer.send(message);
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

        System.err.println("=== TEST: testSendMultipleMessages COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testProducerClose() throws Exception {
        System.err.println("=== TEST: testProducerClose STARTED ===");
        System.err.flush();

        // Send a message first
        producer.send("test message").get(5, TimeUnit.SECONDS);

        // Close producer
        producer.close();

        System.err.println("=== TEST: testProducerClose COMPLETED ===");
        System.err.flush();
    }
}
