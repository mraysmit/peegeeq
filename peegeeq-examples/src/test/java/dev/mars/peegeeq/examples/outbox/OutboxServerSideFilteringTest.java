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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import java.util.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for server-side filtering in the Outbox module.
 * Tests that messages are filtered at the database level using JSONB header queries.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxServerSideFilteringTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxServerSideFilteringTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up OutboxServerSideFilteringTest ===");
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                var databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                outboxFactory = (OutboxFactory) provider.createFactory("outbox", databaseService);
                logger.info("Outbox server-side filtering test setup completed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws InterruptedException {
        logger.info("=== Tearing down OutboxServerSideFilteringTest ===");
        (outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> {
                logger.info("Outbox server-side filtering test teardown completed");
                ctx.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                ctx.completeNow();
            });
        Assertions.assertTrue(ctx.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Outbox server-side filter: headerEquals delivers only matching messages")
    void testOutboxServerSideFilterEquals(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Outbox Server-Side Filter EQUALS ===");
        final int expected = 2;
        String topic = "outbox-filter-equals-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seen = new AtomicInteger();
        Checkpoint settled = testContext.checkpoint(1);

        try {
            consumer.subscribe(message -> {
                logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
                receivedMessages.add(message.getPayload());
                int n = seen.incrementAndGet();
                if (n > expected) {
                    testContext.failNow(new AssertionError("Unexpected extra message after filter: " + message.getPayload()));
                } else if (n == expected) {
                    vertx.timer(500).onSuccess(t -> testContext.verify(() -> {
                        Assertions.assertEquals(expected, receivedMessages.size(),
                            "No additional messages should arrive after settle window");
                        Assertions.assertTrue(receivedMessages.stream().allMatch(m -> m.startsWith("Order")),
                            "All received messages should be ORDER type");
                        settled.flag();
                    }));
                }
                return Future.succeededFuture();
            }).onComplete(testContext.succeeding(ready -> {
                producer.send("Order 1", Map.of("type", "ORDER")).onFailure(testContext::failNow);
                producer.send("Payment 1", Map.of("type", "PAYMENT")).onFailure(testContext::failNow);
                producer.send("Order 2", Map.of("type", "ORDER")).onFailure(testContext::failNow);
                producer.send("Payment 2", Map.of("type", "PAYMENT")).onFailure(testContext::failNow);
                logger.info("Sent 4 messages: 2 ORDER, 2 PAYMENT");
            }));

            Assertions.assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
            if (testContext.failed()) throw testContext.causeOfFailure();

            logger.info("Outbox server-side filter EQUALS test passed: received {} ORDER messages", receivedMessages.size());
        } finally {
            try { consumer.close(); } catch (Exception e) { logger.warn("Consumer close failed", e); }
            try { producer.close(); } catch (Exception e) { logger.warn("Producer close failed", e); }
        }
    }

    @Test
    @DisplayName("Outbox server-side filter: headerIn delivers only set-matching messages")
    void testOutboxServerSideFilterIn(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Outbox Server-Side Filter IN ===");
        final int expected = 3;
        String topic = "outbox-filter-in-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerIn("type", Set.of("ORDER", "REFUND"));
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seen = new AtomicInteger();
        Checkpoint settled = testContext.checkpoint(1);

        try {
            consumer.subscribe(message -> {
                logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
                receivedMessages.add(message.getPayload());
                int n = seen.incrementAndGet();
                if (n > expected) {
                    testContext.failNow(new AssertionError("Unexpected extra message after filter: " + message.getPayload()));
                } else if (n == expected) {
                    vertx.timer(500).onSuccess(t -> testContext.verify(() -> {
                        Assertions.assertEquals(expected, receivedMessages.size(),
                            "No additional messages should arrive after settle window");
                        settled.flag();
                    }));
                }
                return Future.succeededFuture();
            }).onComplete(testContext.succeeding(ready -> {
                producer.send("Order 1", Map.of("type", "ORDER")).onFailure(testContext::failNow);
                producer.send("Payment 1", Map.of("type", "PAYMENT")).onFailure(testContext::failNow);
                producer.send("Refund 1", Map.of("type", "REFUND")).onFailure(testContext::failNow);
                producer.send("Order 2", Map.of("type", "ORDER")).onFailure(testContext::failNow);
                logger.info("Sent 4 messages: 2 ORDER, 1 PAYMENT, 1 REFUND");
            }));

            Assertions.assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
            if (testContext.failed()) throw testContext.causeOfFailure();

            logger.info("Outbox server-side filter IN test passed: received {} messages", receivedMessages.size());
        } finally {
            try { consumer.close(); } catch (Exception e) { logger.warn("Consumer close failed", e); }
            try { producer.close(); } catch (Exception e) { logger.warn("Producer close failed", e); }
        }
    }

    @Test
    @DisplayName("Outbox server-side filter: AND composition delivers only conjunction-matching messages")
    void testOutboxServerSideFilterAnd(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Outbox Server-Side Filter AND ===");
        final int expected = 1;
        String topic = "outbox-filter-and-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.and(
            ServerSideFilter.headerEquals("type", "ORDER"),
            ServerSideFilter.headerEquals("priority", "HIGH")
        );
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seen = new AtomicInteger();
        Checkpoint settled = testContext.checkpoint(1);

        try {
            consumer.subscribe(message -> {
                logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
                receivedMessages.add(message.getPayload());
                int n = seen.incrementAndGet();
                if (n > expected) {
                    testContext.failNow(new AssertionError("Unexpected extra message after filter: " + message.getPayload()));
                } else if (n == expected) {
                    vertx.timer(500).onSuccess(t -> testContext.verify(() -> {
                        Assertions.assertEquals(expected, receivedMessages.size(),
                            "No additional messages should arrive after settle window");
                        Assertions.assertEquals("Order High", receivedMessages.get(0));
                        settled.flag();
                    }));
                }
                return Future.succeededFuture();
            }).onComplete(testContext.succeeding(ready -> {
                producer.send("Order High", Map.of("type", "ORDER", "priority", "HIGH")).onFailure(testContext::failNow);
                producer.send("Order Low", Map.of("type", "ORDER", "priority", "LOW")).onFailure(testContext::failNow);
                producer.send("Payment High", Map.of("type", "PAYMENT", "priority", "HIGH")).onFailure(testContext::failNow);
                logger.info("Sent 3 messages with different type/priority combinations");
            }));

            Assertions.assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
            if (testContext.failed()) throw testContext.causeOfFailure();

            logger.info("Outbox server-side filter AND test passed");
        } finally {
            try { consumer.close(); } catch (Exception e) { logger.warn("Consumer close failed", e); }
            try { producer.close(); } catch (Exception e) { logger.warn("Producer close failed", e); }
        }
    }

    @Test
    @DisplayName("Outbox server-side filter: NULL-safe — messages missing the filter header are excluded")
    void testOutboxServerSideFilterMissingHeader(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Outbox Server-Side Filter with Missing Headers ===");
        final int expected = 1;
        String topic = "outbox-filter-missing-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seen = new AtomicInteger();
        Checkpoint settled = testContext.checkpoint(1);

        try {
            consumer.subscribe(message -> {
                logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
                receivedMessages.add(message.getPayload());
                int n = seen.incrementAndGet();
                if (n > expected) {
                    testContext.failNow(new AssertionError("Unexpected extra message after filter: " + message.getPayload()));
                } else if (n == expected) {
                    vertx.timer(500).onSuccess(t -> testContext.verify(() -> {
                        Assertions.assertEquals(expected, receivedMessages.size(),
                            "No additional messages should arrive after settle window");
                        Assertions.assertEquals("Order With Type", receivedMessages.get(0));
                        settled.flag();
                    }));
                }
                return Future.succeededFuture();
            }).onComplete(testContext.succeeding(ready -> {
                producer.send("Order With Type", Map.of("type", "ORDER")).onFailure(testContext::failNow);
                producer.send("No Type Header 1", Map.of("other", "value")).onFailure(testContext::failNow);
                producer.send("No Type Header 2", Map.of()).onFailure(testContext::failNow);
                logger.info("Sent 3 messages: 1 with type=ORDER, 2 without type header");
            }));

            Assertions.assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
            if (testContext.failed()) throw testContext.causeOfFailure();

            logger.info("Outbox server-side filter missing header test passed - NULL-safe filtering works");
        } finally {
            try { consumer.close(); } catch (Exception e) { logger.warn("Consumer close failed", e); }
            try { producer.close(); } catch (Exception e) { logger.warn("Producer close failed", e); }
        }
    }
}



