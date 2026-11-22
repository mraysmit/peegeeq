package dev.mars.peegeeq.examples.outbox;

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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Promise;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Comprehensive test cases for advanced producer and consumer group functionality
 * based on the ADVANCED_GUIDE.md patterns.
 *
 * Tests high-frequency messaging, consumer groups with filtering, message routing,
 * and performance characteristics.
 *
 * <h3>Refactored Test Design</h3>
 * This test class has been refactored to eliminate poorly structured test design patterns:
 * <ul>
 *   <li><strong>Property Management</strong>: Uses standardized TestContainers configuration</li>
 *   <li><strong>Thread Management</strong>: Uses Vert.x reactive Future composition for async coordination</li>
 *   <li><strong>Test Independence</strong>: Each test uses unique queue and consumer group names</li>
 *   <li><strong>Clean Structure</strong>: Simplified handler methods with essential functionality only</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 2.0 (Refactored)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class AdvancedProducerConsumerGroupTest {
    private static final Logger logger = LoggerFactory.getLogger(AdvancedProducerConsumerGroupTest.class);
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    private String testQueueName;

    /**
     * Generate unique queue name for test independence
     */
    private String getUniqueQueueName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }

    /**
     * Generate unique consumer group name for test independence
     */
    private String getUniqueGroupName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize database schema for outbox tests
        logger.info("Initializing database schema for outbox test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Generate unique queue name for test independence
        testQueueName = getUniqueQueueName("order-events");

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());

        manager.start()
            .onSuccess(v -> {
                // Create queue factory and producer
                DatabaseService databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();

                // Register queue factory implementations
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

                queueFactory = provider.createFactory("outbox", databaseService);
                producer = queueFactory.createProducer(testQueueName, OrderEvent.class);

                logger.info("Test setup completed successfully with queue: {}", testQueueName);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (producer != null) {
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer: {}", e.getMessage()); }
        }
        // The factory close returns a Future: it is awaited before the manager closes, and the
        // manager closes whatever its outcome; the first failure fails the teardown.
        (queueFactory != null ? queueFactory.close() : Future.<Void>succeededFuture())
            .transform(factoryResult -> (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .transform(managerResult -> {
                    if (factoryResult.failed()) {
                        return Future.<Void>failedFuture(factoryResult.cause());
                    }
                    return managerResult.failed()
                        ? Future.<Void>failedFuture(managerResult.cause())
                        : Future.<Void>succeededFuture();
                }))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Teardown failed", err);
                testContext.failNow(err);
            });
    }
    
    /**
     * Test basic high-frequency message production with routing headers.
     */
    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testHighFrequencyProducerWithRouting(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing high-frequency producer with message routing");
        
        final int messageCount = 100;
        final AtomicLong sentMessages = new AtomicLong(0);
        var sendCheckpoint = testContext.checkpoint().asLatch(messageCount);
        
        // Send messages with different routing headers
        for (int i = 1; i <= messageCount; i++) {
            final int messageId = i; // Make effectively final for lambda
            OrderEvent event = createOrderEvent(messageId);
            Map<String, String> headers = createRoutingHeaders(messageId);

            producer.send(event, headers, "correlation-" + messageId, getMessageGroup(headers))
                .onSuccess(result -> {
                    sentMessages.incrementAndGet();
                    sendCheckpoint.countDown();
                })
                .onFailure(testContext::failNow);
        }
        
        // Wait for all messages to be sent
    }

    /**
     * Test region-based consumer groups processing messages in parallel.
     */
    @Test
    void testRegionBasedConsumerGroups(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing region-based consumer groups");

        // Create consumer group for order processing with unique name
        String orderGroupName = getUniqueGroupName("OrderProcessing");
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            orderGroupName, testQueueName, OrderEvent.class);

        // Counters for each region
        AtomicInteger usCount = new AtomicInteger(0);
        AtomicInteger euCount = new AtomicInteger(0);
        AtomicInteger asiaCount = new AtomicInteger(0);

        // The region filters do not overlap and the group gives each message to one eligible
        // consumer, so every message is processed exactly once by its region's consumer.
        final int messagesPerRegion = 10;
        final int expectedTotal = messagesPerRegion * 3;
        AtomicInteger totalProcessedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();

        // Add region-specific consumers
        orderGroup.addConsumer("US-Consumer",
            createRegionHandler(usCount, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byRegion(Set.of("US")));

        orderGroup.addConsumer("EU-Consumer",
            createRegionHandler(euCount, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byRegion(Set.of("EU")));

        orderGroup.addConsumer("ASIA-Consumer",
            createRegionHandler(asiaCount, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byRegion(Set.of("ASIA")));

        // Send only after the group's LISTEN registration has completed.
        orderGroup.start()
            .compose(started -> {
                assertEquals(3, orderGroup.getActiveConsumerCount(),
                    "All three regional consumers should be active");
                return sendRegionalMessages(messagesPerRegion);
            })
            .compose(sent -> allMessagesProcessed.future())
            .transform(TestCleanup.cleanup(() -> orderGroup.close()))
            .onSuccess(ignored -> testContext.verify(() -> {
                logger.info("Region-based processing results: US={}, EU={}, ASIA={}",
                    usCount.get(), euCount.get(), asiaCount.get());
                assertEquals(messagesPerRegion, usCount.get(), "US consumer should process every US message");
                assertEquals(messagesPerRegion, euCount.get(), "EU consumer should process every EU message");
                assertEquals(messagesPerRegion, asiaCount.get(), "ASIA consumer should process every ASIA message");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(20, TimeUnit.SECONDS);
        logger.info("Region-based consumer groups test completed successfully");
    }

    /**
     * Test priority-based consumer groups with different processing speeds.
     */
    @Test
    void testPriorityBasedConsumerGroups(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing priority-based consumer groups");

        // Create consumer group for payment processing with unique name
        String paymentGroupName = getUniqueGroupName("PaymentProcessing");
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            paymentGroupName, testQueueName, OrderEvent.class);

        // Counters for different priorities
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger normalPriorityCount = new AtomicInteger(0);

        // byPriority is a minimum: byPriority("NORMAL") also accepts HIGH. A HIGH message is
        // eligible for both consumers and the group gives it to one of them; a NORMAL message is
        // eligible only for the normal consumer. Each message is processed exactly once.
        final int highPriorityMessages = 5;
        final int normalPriorityMessages = 15;
        final int expectedTotal = highPriorityMessages + normalPriorityMessages;
        AtomicInteger totalProcessedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();
        // A message whose priority the receiving consumer's filter rejects is recorded here.
        List<String> filterViolations = Collections.synchronizedList(new ArrayList<>());

        // Add priority-based consumers
        paymentGroup.addConsumer("HighPriority-Consumer",
            createCheckedHandler("HIGH-PRIORITY", highPriorityCount, 50,
                headers -> "HIGH".equals(headers.get("priority")),
                filterViolations, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byPriority("HIGH"));

        paymentGroup.addConsumer("Normal-Consumer",
            createCheckedHandler("NORMAL-OR-ABOVE", normalPriorityCount, 200,
                headers -> "HIGH".equals(headers.get("priority")) || "NORMAL".equals(headers.get("priority")),
                filterViolations, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byPriority("NORMAL"));

        // Send only after the group's LISTEN registration has completed.
        paymentGroup.start()
            .compose(started -> {
                assertEquals(2, paymentGroup.getActiveConsumerCount(),
                    "Both priority consumers should be active");
                return sendPriorityMessages(highPriorityMessages, normalPriorityMessages);
            })
            .compose(sent -> allMessagesProcessed.future())
            .transform(TestCleanup.cleanup(() -> paymentGroup.close()))
            .onSuccess(ignored -> testContext.verify(() -> {
                logger.info("Priority-based processing results: high={}, normal={}",
                    highPriorityCount.get(), normalPriorityCount.get());
                assertEquals(expectedTotal, highPriorityCount.get() + normalPriorityCount.get(),
                    "Each message should be processed exactly once");
                // With no violations, every NORMAL message reached the normal consumer.
                assertEquals(List.of(), filterViolations,
                    "Each consumer should receive only messages its filter accepts");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(20, TimeUnit.SECONDS);
        logger.info("Priority-based consumer groups test completed successfully");
    }

    /**
     * Test multi-header filtering with complex routing logic.
     */
    @Test
    void testMultiHeaderFilteringConsumerGroups(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing multi-header filtering consumer groups");

        // Create analytics consumer group with unique name
        String analyticsGroupName = getUniqueGroupName("Analytics");
        ConsumerGroup<OrderEvent> analyticsGroup = queueFactory.createConsumerGroup(
            analyticsGroupName, testQueueName, OrderEvent.class);

        // Counters for different analytics consumers
        AtomicInteger premiumUsCount = new AtomicInteger(0);
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger auditCount = new AtomicInteger(0);

        // The filters overlap and the audit consumer accepts everything, so every message has at
        // least one eligible consumer and the group gives it to exactly one of them.
        final int expectedTotal = 30;
        AtomicInteger totalProcessedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();
        // A message whose headers do not match the receiving consumer's filter is recorded here.
        List<String> filterViolations = Collections.synchronizedList(new ArrayList<>());

        // Add consumer for US premium orders
        analyticsGroup.addConsumer("US-Premium-Consumer",
            createCheckedHandler("US-PREMIUM", premiumUsCount, 5,
                headers -> "US".equals(headers.get("region")) && "PREMIUM".equals(headers.get("type")),
                filterViolations, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.and(
                MessageFilter.byRegion(Set.of("US")),
                MessageFilter.byType(Set.of("PREMIUM"))
            ));

        // Add consumer for high-priority orders from any region
        analyticsGroup.addConsumer("HighPriority-Consumer",
            createCheckedHandler("HIGH-PRIORITY", highPriorityCount, 5,
                headers -> "HIGH".equals(headers.get("priority")),
                filterViolations, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.byPriority("HIGH"));

        // Add audit consumer that accepts all messages
        analyticsGroup.addConsumer("Audit-Consumer",
            createCheckedHandler("AUDIT", auditCount, 5, headers -> true,
                filterViolations, totalProcessedCount, expectedTotal, allMessagesProcessed, vertx),
            MessageFilter.acceptAll());

        // Send only after the group's LISTEN registration has completed.
        analyticsGroup.start()
            .compose(started -> {
                assertEquals(3, analyticsGroup.getActiveConsumerCount(),
                    "All analytics consumers should be active");
                return sendComplexFilteringMessages();
            })
            .compose(sent -> allMessagesProcessed.future())
            .transform(TestCleanup.cleanup(() -> analyticsGroup.close()))
            .onSuccess(ignored -> testContext.verify(() -> {
                int totalProcessed = premiumUsCount.get() + highPriorityCount.get() + auditCount.get();
                logger.info("Multi-header filtering results: usPremium={}, highPriority={}, audit={}",
                    premiumUsCount.get(), highPriorityCount.get(), auditCount.get());
                assertEquals(expectedTotal, totalProcessed, "Each message should be processed exactly once");
                assertEquals(List.of(), filterViolations,
                    "Each consumer should receive only messages its filter accepts");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(20, TimeUnit.SECONDS);
        logger.info("Multi-header filtering test completed successfully");
    }

    /**
     * Test concurrent consumer groups competing to process one message stream.
     */
    @Test
    void testConcurrentConsumerGroups(VertxTestContext testContext) throws Exception {
        logger.info("Testing concurrent consumer groups");

        // Create multiple consumer groups with unique names that will process the same messages
        String orderGroupName = getUniqueGroupName("OrderProcessing");
        String paymentGroupName = getUniqueGroupName("PaymentProcessing");
        String analyticsGroupName = getUniqueGroupName("Analytics");
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            orderGroupName, testQueueName, OrderEvent.class);
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            paymentGroupName, testQueueName, OrderEvent.class);
        ConsumerGroup<OrderEvent> analyticsGroup = queueFactory.createConsumerGroup(
            analyticsGroupName, testQueueName, OrderEvent.class);

        final int messageCount = 30;

        // Counters for each group and for completion across the competing groups
        AtomicInteger orderProcessedCount = new AtomicInteger(0);
        AtomicInteger paymentProcessedCount = new AtomicInteger(0);
        AtomicInteger analyticsProcessedCount = new AtomicInteger(0);
        AtomicInteger totalProcessedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();

        // Add consumers to each group
        orderGroup.addConsumer("Order-Consumer",
            createCountingHandler("ORDER", orderProcessedCount, totalProcessedCount,
                messageCount, allMessagesProcessed),
            MessageFilter.acceptAll());

        paymentGroup.addConsumer("Payment-Consumer",
            createCountingHandler("PAYMENT", paymentProcessedCount, totalProcessedCount,
                messageCount, allMessagesProcessed),
            MessageFilter.acceptAll());

        analyticsGroup.addConsumer("Analytics-Consumer",
            createCountingHandler("ANALYTICS", analyticsProcessedCount, totalProcessedCount,
                messageCount, allMessagesProcessed),
            MessageFilter.acceptAll());

        // Await LISTEN registration for every group before publishing to the shared stream.
        Future.all(orderGroup.start(), paymentGroup.start(), analyticsGroup.start())
            .compose(started -> {
                testContext.verify(() -> {
                    assertTrue(orderGroup.isActive(), "Order group should be active before sending");
                    assertTrue(paymentGroup.isActive(), "Payment group should be active before sending");
                    assertTrue(analyticsGroup.isActive(), "Analytics group should be active before sending");
                });
                return sendSimpleMessages(messageCount);
            })
            .compose(sent -> allMessagesProcessed.future())
            .transform(TestCleanup.cleanup(() -> Future.all(
                orderGroup.close(), paymentGroup.close(), analyticsGroup.close()).mapEmpty()))
            .onSuccess(ignored -> testContext.verify(() -> {
                int totalProcessed = orderProcessedCount.get()
                    + paymentProcessedCount.get()
                    + analyticsProcessedCount.get();
                assertEquals(messageCount, totalProcessed,
                    "Competing consumer groups should process each message exactly once");
                assertEquals(messageCount, totalProcessedCount.get(),
                    "Completion counter should match messages sent");

                logger.info("Concurrent group distribution: order={}, payment={}, analytics={}",
                    orderProcessedCount.get(), paymentProcessedCount.get(), analyticsProcessedCount.get());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(30, TimeUnit.SECONDS);
        logger.info("Concurrent consumer groups test completed successfully");
    }

    // Helper Methods

    /**
     * Creates an OrderEvent for testing.
     */
    private OrderEvent createOrderEvent(int id) {
        return new OrderEvent(
            "ORDER-" + id,
            "CREATED",
            100.0 + (id * 10),
            "customer-" + id
        );
    }

    /**
     * Creates routing headers based on message ID.
     */
    private Map<String, String> createRoutingHeaders(long messageId) {
        String region = switch ((int)(messageId % 3)) {
            case 0 -> "US";
            case 1 -> "EU";
            case 2 -> "ASIA";
            default -> "US";
        };

        String priority = (messageId % 10 < 2) ? "HIGH" : "NORMAL";
        String type = (messageId % 5 == 0) ? "PREMIUM" : "STANDARD";

        return Map.of(
            "region", region,
            "priority", priority,
            "type", type,
            "source", "test-service",
            "version", "1.0",
            "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }

    /**
     * Gets message group for ordering.
     */
    private String getMessageGroup(Map<String, String> headers) {
        return headers.get("region") + "-" + headers.get("priority");
    }

    /**
     * Creates a region-specific message handler. It completes {@code allProcessed} when the
     * handlers of the group have finished {@code expectedTotal} messages between them.
     */
    private MessageHandler<OrderEvent> createRegionHandler(AtomicInteger counter,
            AtomicInteger totalCounter, int expectedTotal, Promise<Void> allProcessed, Vertx vertx) {
        return message -> {
            counter.incrementAndGet();
            // Simulate minimal processing time using Vert.x timer
            return vertx.timer(10).<Void>map(t -> {
                countProcessed(totalCounter, expectedTotal, allProcessed);
                return null;
            });
        };
    }

    /**
     * Creates a message handler that checks the headers of each message it receives. A message
     * whose headers fail {@code expectedHeaders} is added to {@code filterViolations}. The handler
     * simulates {@code processingTimeMs} of work, and completes {@code allProcessed} when the
     * handlers of the group have finished {@code expectedTotal} messages between them.
     */
    private MessageHandler<OrderEvent> createCheckedHandler(String name, AtomicInteger counter, int processingTimeMs,
            java.util.function.Predicate<Map<String, String>> expectedHeaders, List<String> filterViolations,
            AtomicInteger totalCounter, int expectedTotal, Promise<Void> allProcessed, Vertx vertx) {
        return message -> {
            counter.incrementAndGet();
            if (!expectedHeaders.test(message.getHeaders())) {
                filterViolations.add(name + " received a message with headers " + message.getHeaders());
            }
            return vertx.timer(processingTimeMs).<Void>map(t -> {
                countProcessed(totalCounter, expectedTotal, allProcessed);
                return null;
            });
        };
    }

    private static void countProcessed(AtomicInteger totalCounter, int expectedTotal, Promise<Void> allProcessed) {
        if (totalCounter.incrementAndGet() == expectedTotal) {
            allProcessed.tryComplete();
        }
    }

    /**
     * Creates a simple counting message handler.
     */
    private MessageHandler<OrderEvent> createCountingHandler(
            String handlerName,
            AtomicInteger counter,
            AtomicInteger totalCounter,
            int expectedTotal,
            Promise<Void> allMessagesProcessed) {
        return message -> {
            counter.incrementAndGet();
            int processed = totalCounter.incrementAndGet();
            logger.debug("[{}] Processed message {} of {}", handlerName, processed, expectedTotal);
            if (processed == expectedTotal) {
                allMessagesProcessed.tryComplete();
            }
            return Future.succeededFuture();
        };
    }

    /**
     * Sends messages with regional distribution.
     */
    private Future<Void> sendRegionalMessages(int messagesPerRegion) {
        String[] regions = {"US", "EU", "ASIA"};

        Future<Void> chain = Future.succeededFuture();
        for (String region : regions) {
            for (int i = 1; i <= messagesPerRegion; i++) {
                final int idx = i;
                final String reg = region;
                chain = chain.compose(v -> {
                    OrderEvent event = createOrderEvent(idx);
                    Map<String, String> headers = Map.of(
                        "region", reg,
                        "priority", "NORMAL",
                        "type", "STANDARD",
                        "source", "test-service"
                    );
                    return producer.send(event, headers, "regional-" + reg + "-" + idx, reg).mapEmpty();
                });
            }
        }

        return chain.onSuccess(v -> logger.info("Sent {} messages per region", messagesPerRegion));
    }

    /**
     * Sends messages with priority distribution.
     */
    private Future<Void> sendPriorityMessages(int highPriorityCount, int normalPriorityCount) {
        Future<Void> chain = Future.succeededFuture();

        // Send high priority messages
        for (int i = 1; i <= highPriorityCount; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "US",
                    "priority", "HIGH",
                    "type", "PREMIUM",
                    "source", "test-service"
                );
                return producer.send(event, headers, "high-priority-" + idx, "HIGH").mapEmpty();
            });
        }

        // Send normal priority messages
        for (int i = 1; i <= normalPriorityCount; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx + highPriorityCount);
                Map<String, String> headers = Map.of(
                    "region", "EU",
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "test-service"
                );
                return producer.send(event, headers, "normal-priority-" + idx, "NORMAL").mapEmpty();
            });
        }

        return chain.onSuccess(v -> logger.info("Sent {} high priority and {} normal priority messages",
            highPriorityCount, normalPriorityCount));
    }

    /**
     * Sends messages with complex filtering combinations.
     */
    private Future<Void> sendComplexFilteringMessages() {
        Future<Void> chain = Future.succeededFuture();

        // Send US Premium messages (should match US-Premium consumer)
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "US",
                    "priority", "HIGH",
                    "type", "PREMIUM",
                    "source", "test-service"
                );
                return producer.send(event, headers, "us-premium-" + idx, "US-PREMIUM").mapEmpty();
            });
        }

        // Send EU High Priority messages (should match high-priority consumer)
        for (int i = 6; i <= 10; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "EU",
                    "priority", "HIGH",
                    "type", "STANDARD",
                    "source", "test-service"
                );
                return producer.send(event, headers, "eu-high-" + idx, "EU-HIGH").mapEmpty();
            });
        }

        // Send various other messages (should only match audit consumer)
        for (int i = 11; i <= 30; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = createRoutingHeaders(idx);
                return producer.send(event, headers, "mixed-" + idx, getMessageGroup(headers)).mapEmpty();
            });
        }

        return chain.onSuccess(v -> logger.info("Sent complex filtering test messages"));
    }

    /**
     * Sends simple messages for concurrent processing tests.
     */
    private Future<Void> sendSimpleMessages(int messageCount) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= messageCount; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "US",
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "test-service"
                );
                return producer.send(event, headers, "simple-" + idx, "SIMPLE").mapEmpty();
            });
        }

        return chain.onSuccess(v -> logger.info("Sent {} simple messages", messageCount));
    }

    /**
     * Simple order event class for testing.
     */
    public static class OrderEvent {
        private String orderId;
        private String status;
        private Double amount;
        private String customerId;

        public OrderEvent() {}

        public OrderEvent(String orderId, String status, Double amount, String customerId) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
            this.customerId = customerId;
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', status='%s', amount=%.2f, customerId='%s'}",
                orderId, status, amount, customerId);
        }
    }
}
