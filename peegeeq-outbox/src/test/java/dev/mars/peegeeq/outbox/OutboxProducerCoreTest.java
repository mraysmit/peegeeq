package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for OutboxProducer.
 * Tests producer functionality with real database using TestContainers.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxProducerCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerCoreTest.class);

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.polling-interval"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== OutboxProducerCoreTest SETUP STARTED ===");
        System.err.flush();

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
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.polling-interval", "PT0.5S");

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("producer-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);

        System.err.println("=== OutboxProducerCoreTest SETUP COMPLETED ===");
        System.err.flush();
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        System.err.println("=== OutboxProducerCoreTest TEARDOWN STARTED ===");
        System.err.flush();

        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> tearDownContext.completeNow())
                    .onFailure(tearDownContext::failNow);
            assertTrue(tearDownContext.awaitCompletion(10, TimeUnit.SECONDS));
        } else {
            tearDownContext.completeNow();
        }

        // Clear system properties
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }

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
        logger.info("Test: producer creation");
        System.err.println("=== TEST: testSendBasicMessage STARTED ===");
        System.err.flush();

        String testMessage = "Hello, OutboxProducer Test!";

        Future<Void> sendFuture = producer.send(testMessage);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.await();

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

        Future<Void> sendFuture = producer.send(testMessage, headers);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.await();

        System.err.println("=== TEST: testSendMessageWithHeaders COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMessageWithCorrelationId() throws Exception {
        logger.info("Test: send message with headers");
        System.err.println("=== TEST: testSendMessageWithCorrelationId STARTED ===");
        System.err.flush();

        String testMessage = "Message with correlation ID";
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        String correlationId = UUID.randomUUID().toString();

        Future<Void> sendFuture = producer.send(testMessage, headers, correlationId);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.await();

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

        Future<Void> sendFuture = producer.send(testMessage, headers, correlationId, messageGroup);
        assertNotNull(sendFuture, "Send should return a future");

        // Wait for send to complete
        sendFuture.await();

        System.err.println("=== TEST: testSendMessageWithAllParameters COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testSendMultipleMessages() throws Exception {
        logger.info("Test: send message with all parameters");
        System.err.println("=== TEST: testSendMultipleMessages STARTED ===");
        System.err.flush();

        int messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            String message = "Message " + i;
            producer.send(message).await();
        }

        System.err.println("=== TEST: testSendMultipleMessages COMPLETED ===");
        System.err.flush();
    }

    @Test
    void testProducerClose() throws Exception {
        System.err.println("=== TEST: testProducerClose STARTED ===");
        System.err.flush();

        // Send a message first
        producer.send("test message").await();

        // Close producer
        producer.close();

        System.err.println("=== TEST: testProducerClose COMPLETED ===");
        System.err.flush();
    }
}


