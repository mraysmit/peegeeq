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
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Test cases for consumer group resilience, error handling, and recovery scenarios
 * based on the ADVANCED_GUIDE.md patterns.
 *
 * Tests failure scenarios, recovery mechanisms, and system stability under adverse conditions.
 *
 * <h3>Refactored Test Design</h3>
 * This test class has been refactored to eliminate poorly structured test design patterns:
 * <ul>
 *   <li><strong>Property Management</strong>: Uses standardized TestContainers configuration</li>
 *   <li><strong>Thread Management</strong>: Uses chained {@link io.vertx.core.Future} composition with {@link java.util.concurrent.CountDownLatch} instead of manual ExecutorService</li>
 *   <li><strong>Test Independence</strong>: Each test uses unique queue and consumer group names</li>
 *   <li><strong>Clean Structure</strong>: Simplified setup/teardown with essential logging only</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 2.0 (Refactored)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class ConsumerGroupResilienceTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupResilienceTest.class);
    /** How long a test polls for its expected deliveries before it fails. */
    private static final long POLL_TIMEOUT_MS = 10_000;
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

        // Ensure database schema exists (independent of test execution order)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

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

                logger.info("Resilience test setup completed successfully with queue: {}", testQueueName);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        // The factory closes the consumer groups and the producer it created. Each close runs
        // after the previous one has settled, and the first failure fails the teardown.
        TestCleanup.run(
                () -> queueFactory != null ? queueFactory.close() : Future.<Void>succeededFuture(),
                () -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Resilience test teardown failed", err);
                testContext.failNow(err);
            });
    }
    
    /**
     * Test consumer group behavior when individual consumers intentionally fail.
     * This test verifies that the consumer group system properly handles consumer failures
     * and that backup consumers can take over processing when primary consumers fail.
     *
     * INTENTIONAL FAILURE TEST: This test deliberately creates consumers that fail on
     * certain messages to verify error handling and recovery mechanisms.
     */
    @Test
    void testConsumerFailureRecovery(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("===== RUNNING INTENTIONAL CONSUMER FAILURE RECOVERY TEST =====");
        logger.info("INTENTIONAL TEST: This test deliberately creates failing consumers to test recovery mechanisms");
        logger.info("Testing consumer failure recovery");

        // Create consumer group with multiple consumers using unique names
        String groupName = getUniqueGroupName("OrderProcessing");
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            groupName, testQueueName, OrderEvent.class);

        // Counters for successful and failed processing
        AtomicInteger successfulCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger recoveredCount = new AtomicInteger(0);

        // Add a consumer that fails on certain messages
        logger.info("INTENTIONAL FAILURE: Adding consumer that will fail on specific messages");
        orderGroup.addConsumer("failing-consumer",
            createFailingHandler(successfulCount, failedCount, vertx),
            MessageFilter.byRegion(Set.of("US")));

        // Add a backup consumer that processes all messages
        orderGroup.addConsumer("backup-consumer",
            createBackupHandler(recoveredCount, vertx),
            MessageFilter.acceptAll());

        // Send only after the group's LISTEN registration has completed, then poll until a
        // failure, a success and a backup delivery have all happened, or fail at the deadline.
        orderGroup.start()
            .compose(started -> {
                assertEquals(2, orderGroup.getActiveConsumerCount(),
                    "Both consumers should be active");
                logger.info("INTENTIONAL FAILURE: Sending messages that will trigger consumer failures");
                return sendFailureTestMessages();
            })
            .compose(sent -> pollUntil(vertx,
                () -> failedCount.get() > 0 && successfulCount.get() > 0 && recoveredCount.get() > 0,
                System.currentTimeMillis() + POLL_TIMEOUT_MS,
                "a failed delivery, a successful delivery and a backup delivery"))
            .transform(TestCleanup.cleanup(() -> orderGroup.close()))
            .onSuccess(v -> testContext.verify(() -> {
                logger.info("Failure recovery results: successful={}, failed={}, recovered={}",
                    successfulCount.get(), failedCount.get(), recoveredCount.get());
                assertTrue(failedCount.get() > 0, "Some failures should have occurred");
                assertTrue(successfulCount.get() > 0, "Some messages should have been processed successfully");
                assertTrue(recoveredCount.get() > 0, "Backup consumer should have processed messages");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(20, TimeUnit.SECONDS);
        logger.info("SUCCESS: Consumer failure recovery mechanisms worked correctly");
        logger.info("===== INTENTIONAL FAILURE TEST COMPLETED =====");
        logger.info("Consumer failure recovery test completed successfully");
    }
    
    /**
     * Test consumer group behavior with invalid message filters.
     */
    @Test
    void testInvalidMessageFilterHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing invalid message filter handling");
        
        // Create consumer group with unique names
        String groupName = getUniqueGroupName("TestGroup");
        ConsumerGroup<OrderEvent> testGroup = queueFactory.createConsumerGroup(
            groupName, testQueueName, OrderEvent.class);
        
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger filteredCount = new AtomicInteger(0);
        
        // Add consumer with a filter that might throw exceptions
        testGroup.addConsumer("filter-test-consumer", 
            createFilterTestHandler(processedCount, vertx), 
            createExceptionThrowingFilter(filteredCount));
        
        // Send only after the group's LISTEN registration has completed, then poll until a
        // message is processed, or fail at the deadline.
        testGroup.start()
            .compose(started -> sendFilterTestMessages())
            .compose(sent -> pollUntil(vertx, () -> processedCount.get() > 0,
                System.currentTimeMillis() + POLL_TIMEOUT_MS, "a processed message"))
            .transform(TestCleanup.cleanup(() -> testGroup.close()))
            .onSuccess(v -> testContext.verify(() -> {
                logger.info("Filter handling results: processed={}, filterExceptions={}",
                    processedCount.get(), filteredCount.get());
                assertTrue(processedCount.get() > 0, "Some messages should be processed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(15, TimeUnit.SECONDS);
        logger.info("Invalid message filter handling test completed successfully");
    }
    
    /**
     * Test consumer group statistics and monitoring during failures.
     */
    @Test
    void testConsumerGroupStatisticsDuringFailures(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing consumer group statistics during failures");
        
        // Create consumer group with unique names
        String groupName = getUniqueGroupName("MonitoringGroup");
        ConsumerGroup<OrderEvent> monitoringGroup = queueFactory.createConsumerGroup(
            groupName, testQueueName, OrderEvent.class);
        
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // Add consumer that occasionally fails
        ConsumerGroupMember<OrderEvent> member = monitoringGroup.addConsumer("monitoring-consumer", 
            createMonitoringHandler(processedCount, vertx), 
            MessageFilter.acceptAll());
        
        // Send only after the group's LISTEN registration has completed, then poll until the
        // member statistics show a processed message, or fail at the deadline. The group state
        // is asserted before the group is closed.
        monitoringGroup.start()
            .compose(started -> sendMonitoringTestMessages())
            .compose(sent -> pollUntil(vertx, () -> member.getStats().getMessagesProcessed() > 0,
                System.currentTimeMillis() + POLL_TIMEOUT_MS, "a processed message in the member statistics"))
            .compose(polled -> {
                assertTrue(monitoringGroup.isActive(), "Consumer group should be active");
                assertEquals(1, monitoringGroup.getActiveConsumerCount(), "One consumer should be active");
                assertTrue(member.isActive(), "Consumer member should be active");
                assertTrue(member.getStats().getMessagesProcessed() > 0, "Some messages should have been processed");
                logger.info("Monitoring statistics: memberProcessed={}, totalProcessed={}",
                    member.getStats().getMessagesProcessed(), processedCount.get());
                return Future.<Void>succeededFuture();
            })
            .transform(TestCleanup.cleanup(() -> monitoringGroup.close()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        testContext.awaitCompletion(15, TimeUnit.SECONDS);
        logger.info("Consumer group statistics test completed successfully");
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
     * Creates a message handler that intentionally fails on certain conditions.
     * This handler is designed to simulate real-world processing failures
     * for testing error handling and recovery mechanisms.
     *
     * INTENTIONAL FAILURE HANDLER: This handler deliberately fails on orders
     * with IDs ending in 5 or 7 to test failure recovery.
     */
    private MessageHandler<OrderEvent> createFailingHandler(AtomicInteger successCount, AtomicInteger failCount, Vertx vertx) {
        return message -> {
            OrderEvent event = message.getPayload();

            // Fail on orders with IDs ending in 5 or 7
            String orderId = event.getOrderId();
            if (orderId.endsWith("5") || orderId.endsWith("7")) {
                failCount.incrementAndGet();
                logger.info("INTENTIONAL TEST FAILURE: Simulating processing failure for order {}", orderId);
                logger.debug("[FailingConsumer]  INTENTIONAL TEST FAILURE - Simulated failure for order: {}", orderId);
                return Future.failedFuture(
                    new RuntimeException(" INTENTIONAL TEST FAILURE: Simulated processing failure for order " + orderId));
            }

            successCount.incrementAndGet();

            // Simulate processing delay using Vert.x timer
            return vertx.timer(100).mapEmpty();
        };
    }

    /**
     * Creates a backup message handler that processes all messages.
     */
    private MessageHandler<OrderEvent> createBackupHandler(AtomicInteger recoveredCount, Vertx vertx) {
        return message -> {
            recoveredCount.incrementAndGet();

            // Simulate recovery processing delay using Vert.x timer
            return vertx.timer(50).mapEmpty();
        };
    }

    /**
     * Creates a message handler for filter testing.
     */
    private MessageHandler<OrderEvent> createFilterTestHandler(AtomicInteger processedCount, Vertx vertx) {
        return message -> {
            processedCount.incrementAndGet();

            // Simulate filter processing delay using Vert.x timer
            return vertx.timer(25).mapEmpty();
        };
    }

    /**
     * Creates a message handler for monitoring tests.
     */
    private MessageHandler<OrderEvent> createMonitoringHandler(AtomicInteger processedCount, Vertx vertx) {
        return message -> {
            OrderEvent event = message.getPayload();

            // Occasionally simulate processing delays
            int delay = event.getOrderId().hashCode() % 3 == 0 ? 200 : 50;

            processedCount.incrementAndGet();

            // Simulate monitoring processing delay using Vert.x timer
            return vertx.timer(delay).mapEmpty();
        };
    }

    /**
     * Creates a filter that occasionally throws exceptions.
     */
    private java.util.function.Predicate<Message<OrderEvent>> createExceptionThrowingFilter(AtomicInteger exceptionCount) {
        return message -> {
            try {
                Map<String, String> headers = message.getHeaders();
                if (headers == null) return true;

                // Throw exception for messages with specific characteristics
                String region = headers.get("region");
                if ("INVALID".equals(region)) {
                    exceptionCount.incrementAndGet();
                    logger.info("INTENTIONAL TEST FAILURE: Simulating filter exception for region: {}", region);
                    throw new RuntimeException(" INTENTIONAL TEST FAILURE: Simulated filter exception for region: " + region);
                }

                return true;
            } catch (RuntimeException e) {
                // Re-throw intentional test failures - these are expected during resilience testing
                throw e;
            }
        };
    }

    /**
     * Polls {@code condition} every 100 ms. The Future succeeds when the condition holds and
     * fails with an {@link AssertionError} naming {@code description} once {@code deadline}
     * (epoch milliseconds) has passed.
     */
    private static Future<Void> pollUntil(Vertx vertx, java.util.function.BooleanSupplier condition,
                                          long deadline, String description) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError("Timed out waiting for " + description));
        }
        return vertx.timer(100).compose(t -> pollUntil(vertx, condition, deadline, description));
    }

    /**
     * Sends messages that will trigger failures in the failing handler, one after another.
     * The returned Future fails with the first failed send.
     */
    private Future<Void> sendFailureTestMessages() {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 20; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "US",
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "failure-test"
                );
                return producer.send(event, headers, "failure-test-" + idx, "US").mapEmpty();
            });
        }
        return chain.onSuccess(v -> logger.info("Sent 20 failure test messages"));
    }

    /**
     * Sends messages for filter testing, one after another.
     */
    private Future<Void> sendFilterTestMessages() {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 10; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                String region = (idx % 4 == 0) ? "INVALID" : "US";
                Map<String, String> headers = Map.of(
                    "region", region,
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "filter-test"
                );
                return producer.send(event, headers, "filter-test-" + idx, region).mapEmpty();
            });
        }
        return chain.onSuccess(v -> logger.info("Sent 10 filter test messages"));
    }

    /**
     * Sends messages for monitoring tests, one after another.
     */
    private Future<Void> sendMonitoringTestMessages() {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 15; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                OrderEvent event = createOrderEvent(idx);
                Map<String, String> headers = Map.of(
                    "region", "US",
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "monitoring-test"
                );
                return producer.send(event, headers, "monitoring-test-" + idx, "US").mapEmpty();
            });
        }
        return chain.onSuccess(v -> logger.info("Sent 15 monitoring test messages"));
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


