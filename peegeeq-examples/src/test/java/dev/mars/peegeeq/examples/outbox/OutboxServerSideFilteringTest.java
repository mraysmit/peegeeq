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

package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxConsumerConfig;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for server-side filtering in the Outbox module.
 * Tests that messages are filtered at the database level using JSONB header queries.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OutboxServerSideFilteringTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxServerSideFilteringTest.class);

    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;

    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up OutboxServerSideFilteringTest ===");
        configureSystemPropertiesForContainer();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("outbox-filter-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        outboxFactory = (OutboxFactory) provider.createFactory("outbox", databaseService);
        logger.info("Outbox server-side filtering test setup completed");
    }

    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down OutboxServerSideFilteringTest ===");
        if (outboxFactory != null) {
            try {
                outboxFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing outbox factory: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.close();
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.error("Error during manager cleanup", e);
            }
        }
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        logger.info("Outbox server-side filtering test teardown completed");
    }

    @Test
    @Order(1)
    void testOutboxServerSideFilterEquals() throws Exception {
        logger.info("=== Testing Outbox Server-Side Filter EQUALS ===");
        String topic = "outbox-filter-equals-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type=ORDER
        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        Thread.sleep(2000);

        // Send messages with different types
        producer.send("Order 1", Map.of("type", "ORDER")).get(10, TimeUnit.SECONDS);
        producer.send("Payment 1", Map.of("type", "PAYMENT")).get(10, TimeUnit.SECONDS);
        producer.send("Order 2", Map.of("type", "ORDER")).get(10, TimeUnit.SECONDS);
        producer.send("Payment 2", Map.of("type", "PAYMENT")).get(10, TimeUnit.SECONDS);
        logger.info("Sent 4 messages: 2 ORDER, 2 PAYMENT");

        boolean received = latch.await(30, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(2, receivedMessages.size(), "Should receive exactly 2 ORDER messages");
        Assertions.assertTrue(receivedMessages.stream().allMatch(m -> m.startsWith("Order")),
            "All received messages should be ORDER type");

        logger.info("Outbox server-side filter EQUALS test passed: received {} ORDER messages", receivedMessages.size());

        consumer.close();
        producer.close();
    }

    @Test
    @Order(2)
    void testOutboxServerSideFilterIn() throws Exception {
        logger.info("=== Testing Outbox Server-Side Filter IN ===");
        String topic = "outbox-filter-in-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type IN (ORDER, REFUND)
        ServerSideFilter filter = ServerSideFilter.headerIn("type", Set.of("ORDER", "REFUND"));
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        Thread.sleep(2000);

        producer.send("Order 1", Map.of("type", "ORDER")).get(10, TimeUnit.SECONDS);
        producer.send("Payment 1", Map.of("type", "PAYMENT")).get(10, TimeUnit.SECONDS);
        producer.send("Refund 1", Map.of("type", "REFUND")).get(10, TimeUnit.SECONDS);
        producer.send("Order 2", Map.of("type", "ORDER")).get(10, TimeUnit.SECONDS);
        logger.info("Sent 4 messages: 2 ORDER, 1 PAYMENT, 1 REFUND");

        boolean received = latch.await(30, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(3, receivedMessages.size(), "Should receive 3 messages (ORDER + REFUND)");

        logger.info("Outbox server-side filter IN test passed: received {} messages", receivedMessages.size());

        consumer.close();
        producer.close();
    }

    @Test
    @Order(3)
    void testOutboxServerSideFilterAnd() throws Exception {
        logger.info("=== Testing Outbox Server-Side Filter AND ===");
        String topic = "outbox-filter-and-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type=ORDER AND priority=HIGH
        ServerSideFilter filter = ServerSideFilter.and(
            ServerSideFilter.headerEquals("type", "ORDER"),
            ServerSideFilter.headerEquals("priority", "HIGH")
        );
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        Thread.sleep(2000);

        producer.send("Order High", Map.of("type", "ORDER", "priority", "HIGH")).get(10, TimeUnit.SECONDS);
        producer.send("Order Low", Map.of("type", "ORDER", "priority", "LOW")).get(10, TimeUnit.SECONDS);
        producer.send("Payment High", Map.of("type", "PAYMENT", "priority", "HIGH")).get(10, TimeUnit.SECONDS);
        logger.info("Sent 3 messages with different type/priority combinations");

        boolean received = latch.await(30, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered message");
        Assertions.assertEquals(1, receivedMessages.size(), "Should receive exactly 1 message");
        Assertions.assertEquals("Order High", receivedMessages.get(0));

        logger.info("Outbox server-side filter AND test passed");

        consumer.close();
        producer.close();
    }

    @Test
    @Order(4)
    void testOutboxServerSideFilterMissingHeader() throws Exception {
        logger.info("=== Testing Outbox Server-Side Filter with Missing Headers ===");
        String topic = "outbox-filter-missing-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        // Create consumer with filter for type=ORDER
        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        Thread.sleep(2000);

        // Send messages - some without the 'type' header
        producer.send("Order With Type", Map.of("type", "ORDER")).get(10, TimeUnit.SECONDS);
        producer.send("No Type Header 1", Map.of("other", "value")).get(10, TimeUnit.SECONDS);
        producer.send("No Type Header 2", Map.of()).get(10, TimeUnit.SECONDS);
        logger.info("Sent 3 messages: 1 with type=ORDER, 2 without type header");

        boolean received = latch.await(30, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered message");
        Assertions.assertEquals(1, receivedMessages.size(), "Should receive exactly 1 message with type=ORDER");
        Assertions.assertEquals("Order With Type", receivedMessages.get(0));

        logger.info("Outbox server-side filter missing header test passed - NULL-safe filtering works");

        consumer.close();
        producer.close();
    }
}

