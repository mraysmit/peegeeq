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

package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.pgqueue.PgNativeQueueFactory;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Integration tests for server-side filtering feature.
 * Tests that messages are filtered at the database level using JSONB header queries.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServerSideFilteringTest {

    private static final Logger logger = LoggerFactory.getLogger(ServerSideFilteringTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgNativeQueueFactory nativeFactory;

    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up ServerSideFilteringTest ===");
        configureSystemPropertiesForContainer();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("filter-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        nativeFactory = (PgNativeQueueFactory) provider.createFactory("native", databaseService);
        logger.info("Server-side filtering test setup completed");
    }

    @AfterEach
    void tearDown(Vertx vertx) {
        logger.info("=== Tearing down ServerSideFilteringTest ===");
        if (nativeFactory != null) {
            try {
                nativeFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing native factory: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.closeReactive().toCompletionStage().toCompletableFuture().join();
                CompletableFuture<Void> delay = new CompletableFuture<>();
                vertx.setTimer(2000, id -> delay.complete(null));
                delay.join();
            } catch (Exception e) {
                logger.error("Error during manager cleanup", e);
            }
        }
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        logger.info("Server-side filtering test teardown completed");
    }

    @Test
    @Order(1)
    void testServerSideFilterEquals(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter EQUALS ===");
        String topic = "filter-equals-test-" + System.currentTimeMillis();

        // Create producer
        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type=ORDER
        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages with different types
            producer.send("Order 1", Map.of("type", "ORDER"));
            producer.send("Payment 1", Map.of("type", "PAYMENT"));
            producer.send("Order 2", Map.of("type", "ORDER"));
            producer.send("Payment 2", Map.of("type", "PAYMENT"));
            logger.info("Sent 4 messages: 2 ORDER, 2 PAYMENT");
        });

        // Wait for filtered messages
        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        // Verify only ORDER messages were received
        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(2, receivedMessages.size(), "Should receive exactly 2 ORDER messages");
        Assertions.assertTrue(receivedMessages.stream().allMatch(m -> m.startsWith("Order")),
            "All received messages should be ORDER type");

        logger.info("Server-side filter EQUALS test passed: received {} ORDER messages", receivedMessages.size());

        consumer.close();
        producer.close();
    }

    @Test
    @Order(2)
    void testServerSideFilterIn(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter IN ===");
        String topic = "filter-in-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type IN (ORDER, REFUND)
        ServerSideFilter filter = ServerSideFilter.headerIn("type", Set.of("ORDER", "REFUND"));
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages with different types
            producer.send("Order 1", Map.of("type", "ORDER"));
            producer.send("Payment 1", Map.of("type", "PAYMENT"));
            producer.send("Refund 1", Map.of("type", "REFUND"));
            producer.send("Order 2", Map.of("type", "ORDER"));
            logger.info("Sent 4 messages: 2 ORDER, 1 PAYMENT, 1 REFUND");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(3, receivedMessages.size(), "Should receive exactly 3 messages (ORDER + REFUND)");

        logger.info("Server-side filter IN test passed: received {} messages", receivedMessages.size());

        consumer.close();
        producer.close();
    }

    @Test
    @Order(3)
    void testServerSideFilterAnd(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter AND ===");
        String topic = "filter-and-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with server-side filter for type=ORDER AND priority=HIGH
        ServerSideFilter filter = ServerSideFilter.and(
            ServerSideFilter.headerEquals("type", "ORDER"),
            ServerSideFilter.headerEquals("priority", "HIGH")
        );
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(1);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages with different combinations
            producer.send("Order Low", Map.of("type", "ORDER", "priority", "LOW"));
            producer.send("Payment High", Map.of("type", "PAYMENT", "priority", "HIGH"));
            producer.send("Order High", Map.of("type", "ORDER", "priority", "HIGH"));
            logger.info("Sent 3 messages with different type/priority combinations");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered message");
        Assertions.assertEquals(1, receivedMessages.size(), "Should receive exactly 1 message (ORDER + HIGH)");
        Assertions.assertEquals("Order High", receivedMessages.get(0));

        logger.info("Server-side filter AND test passed");

        consumer.close();
        producer.close();
    }

    @Test
    @Order(4)
    void testServerSideFilterNotEquals(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter NOT_EQUALS ===");
        String topic = "filter-not-equals-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer that excludes CANCELLED orders
        ServerSideFilter filter = ServerSideFilter.headerNotEquals("status", "CANCELLED");
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages with different statuses
            producer.send("Order Active", Map.of("status", "ACTIVE"));
            producer.send("Order Cancelled", Map.of("status", "CANCELLED"));
            producer.send("Order Pending", Map.of("status", "PENDING"));
            logger.info("Sent 3 messages: ACTIVE, CANCELLED, PENDING");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(2, receivedMessages.size(), "Should receive 2 non-CANCELLED messages");
        Assertions.assertTrue(receivedMessages.contains("Order Active"));
        Assertions.assertTrue(receivedMessages.contains("Order Pending"));
        Assertions.assertFalse(receivedMessages.contains("Order Cancelled"));

        logger.info("Server-side filter NOT_EQUALS test passed");

        consumer.close();
        producer.close();
    }

    @Test
    @Order(5)
    void testServerSideFilterOr(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter OR ===");
        String topic = "filter-or-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with OR filter: type=ORDER OR priority=URGENT
        ServerSideFilter filter = ServerSideFilter.or(
            ServerSideFilter.headerEquals("type", "ORDER"),
            ServerSideFilter.headerEquals("priority", "URGENT")
        );
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages
            producer.send("Order Normal", Map.of("type", "ORDER", "priority", "NORMAL"));
            producer.send("Payment Urgent", Map.of("type", "PAYMENT", "priority", "URGENT"));
            producer.send("Payment Normal", Map.of("type", "PAYMENT", "priority", "NORMAL"));
            producer.send("Order Urgent", Map.of("type", "ORDER", "priority", "URGENT"));
            logger.info("Sent 4 messages with different type/priority combinations");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(3, receivedMessages.size(), "Should receive 3 messages (ORDER or URGENT)");
        Assertions.assertFalse(receivedMessages.contains("Payment Normal"), "Should not receive PAYMENT+NORMAL");

        logger.info("Server-side filter OR test passed");

        consumer.close();
        producer.close();
    }

    @Test
    @Order(6)
    void testServerSideFilterLike(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter LIKE ===");
        String topic = "filter-like-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with LIKE filter for event types starting with "order-"
        ServerSideFilter filter = ServerSideFilter.headerLike("eventType", "order-%");
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages with different event types
            producer.send("Order Created", Map.of("eventType", "order-created"));
            producer.send("Payment Received", Map.of("eventType", "payment-received"));
            producer.send("Order Shipped", Map.of("eventType", "order-shipped"));
            logger.info("Sent 3 messages with different eventType patterns");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive filtered messages");
        Assertions.assertEquals(2, receivedMessages.size(), "Should receive 2 order-* messages");
        Assertions.assertTrue(receivedMessages.contains("Order Created"));
        Assertions.assertTrue(receivedMessages.contains("Order Shipped"));

        logger.info("Server-side filter LIKE test passed");

        consumer.close();
        producer.close();
    }

    @Test
    @Order(7)
    void testServerSideFilterMissingHeader(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Server-Side Filter with Missing Headers ===");
        String topic = "filter-missing-header-test-" + System.currentTimeMillis();

        MessageProducer<String> producer = nativeFactory.createProducer(topic, String.class);

        // Create consumer with filter for type=ORDER
        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        ConsumerConfig config = ConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = nativeFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Checkpoint checkpoint = testContext.checkpoint(1);

        consumer.subscribe(message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            checkpoint.flag();
            return CompletableFuture.completedFuture(null);
        });

        vertx.setTimer(2000, id -> {
            // Send messages - some without the 'type' header
            producer.send("No Header", Map.of("other", "value"));
            producer.send("Order With Type", Map.of("type", "ORDER"));
            producer.send("Empty Headers", Map.of());
            logger.info("Sent 3 messages: 1 with type=ORDER, 2 without type header");
        });

        boolean received = testContext.awaitCompletion(15, TimeUnit.SECONDS);

        Assertions.assertTrue(received, "Should receive the ORDER message");
        Assertions.assertEquals(1, receivedMessages.size(), "Should receive only 1 message with type=ORDER");
        Assertions.assertEquals("Order With Type", receivedMessages.get(0));

        logger.info("Server-side filter missing header test passed - NULL-safe filtering works");

        consumer.close();
        producer.close();
    }
}



