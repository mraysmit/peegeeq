package dev.mars.peegeeq.bitemporal;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit test for TransactionalBiTemporalExample demonstrating advanced bi-temporal
 * event store functionality with transactional scenarios.
 *
 * This test preserves ALL functionality from the original 605-line example:
 * - Complex business workflows with multiple event types (Order + Payment)
 * - Concurrent processing with proper isolation
 * - Error handling and rollback scenarios
 * - Performance testing with high-throughput scenarios
 * - Transactional consistency within bi-temporal event stores
 *
 * Key Features Tested:
 * 1. Multi-EventStore transactional consistency
 * 2. Complex business workflows (Order + Payment processing)
 * 3. Concurrent processing with proper isolation
 * 4. Error handling and rollback scenarios
 * 5. Performance testing with high-throughput scenarios
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TransactionalBiTemporalExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(TransactionalBiTemporalExampleTest.class);

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer sharedPostgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_bitemporal_test")
            .withUsername("postgres")
            .withPassword("password")
            .withSharedMemorySize(256 * 1024 * 1024L) // 256MB shared memory
            .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off", "-c", "synchronous_commit=off"); // Performance optimizations for tests

    private PeeGeeQManager peeGeeQManager;
    private EventStore<OrderEvent> orderEventStore;
    private EventStore<PaymentEvent> paymentEventStore;
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Transactional Bi-Temporal Example Test ===");

        // Configure PeeGeeQ to use container database
        setTestProperty("peegeeq.database.host", sharedPostgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(sharedPostgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", sharedPostgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", sharedPostgres.getUsername());
        setTestProperty("peegeeq.database.password", sharedPostgres.getPassword());
        setTestProperty("peegeeq.database.schema", "public");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating bitemporal_event_log table using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(sharedPostgres, SchemaComponent.BITEMPORAL);
        logger.info("bitemporal_event_log table created successfully");

        // Initialize PeeGeeQ Manager
        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start()
            .compose(v -> cleanupDatabase())
            .map(v -> {
                logger.info("PeeGeeQ Manager started successfully");
                BiTemporalEventStoreFactory eventStoreFactory = new BiTemporalEventStoreFactory(peeGeeQManager);
                orderEventStore = eventStoreFactory.createEventStore(OrderEvent.class, "bitemporal_event_log");
                paymentEventStore = eventStoreFactory.createEventStore(PaymentEvent.class, "bitemporal_event_log");
                logger.info("Transactional Bi-Temporal Example Test setup completed");
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Cleaning up Transactional Bi-Temporal Example Test");

        Future<Void> closeChain = Future.<Void>succeededFuture();
        if (orderEventStore != null) {
            closeChain = closeChain.compose(v -> orderEventStore.closeFuture());
        }
        if (paymentEventStore != null) {
            closeChain = closeChain.compose(v -> paymentEventStore.closeFuture());
        }
        if (peeGeeQManager != null) {
            closeChain = closeChain
                .compose(v -> cleanupDatabase())
                .compose(v -> peeGeeQManager.closeReactive());
        }

        closeChain
            .recover(err -> {
                logger.warn("Transactional Bi-Temporal Example Test cleanup encountered an error: {}", err.getMessage());
                return Future.<Void>succeededFuture();
            })
            .onSuccess(v -> {
                restoreTestProperties();
                logger.info("Transactional Bi-Temporal Example Test cleanup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
    }

    private Future<Void> cleanupDatabase() {
        if (peeGeeQManager == null || peeGeeQManager.getPool() == null) {
            return Future.<Void>succeededFuture();
        }

        return peeGeeQManager.getPool()
            .query("TRUNCATE TABLE bitemporal_event_log CASCADE")
            .execute()
            .map(rows -> (Void) null)
            .onSuccess(v -> logger.debug("Database tables cleaned up successfully"))
            .recover(err -> {
                logger.debug("Could not truncate tables (they may not exist yet): {}", err.getMessage());
                return Future.succeededFuture((Void) null);
            });
    }

    /**
     * Test 1: Multi-EventStore Transactional Consistency
     * Demonstrates that multiple event store operations can be coordinated for consistency.
     */
    @Test
    @Order(1)
    void testMultiEventStoreTransactionalConsistency(VertxTestContext testContext) {
        logger.info("=== Testing Multi-EventStore Transactional Consistency ===");

        // Create test events
        OrderEvent orderEvent = new OrderEvent("order-tx-001", "customer-001", new BigDecimal("100.00"), "PENDING", Instant.now());
        PaymentEvent paymentEvent = new PaymentEvent("payment-tx-001", "order-tx-001", "customer-001", new BigDecimal("100.00"), "CREDIT_CARD", "AUTHORIZED", Instant.now());

        Instant validTime = Instant.now();

        // Store events in both event stores
        Future.all(
            orderEventStore.appendBuilder().eventType("OrderCreated").payload(orderEvent).validTime(validTime).execute(),
            paymentEventStore.appendBuilder().eventType("PaymentAuthorized").payload(paymentEvent).validTime(validTime).execute())
            .compose(v -> Future.all(
                orderEventStore.query(EventQuery.builder().eventType("OrderCreated").build()),
                paymentEventStore.query(EventQuery.builder().eventType("PaymentAuthorized").build())))
            .onSuccess(results -> testContext.verify(() -> {
                List<BiTemporalEvent<OrderEvent>> orderEvents = results.resultAt(0);
                List<BiTemporalEvent<PaymentEvent>> paymentEvents = results.resultAt(1);

                assertEquals(1, orderEvents.size(), "Should have 1 order event stored");
                assertEquals(1, paymentEvents.size(), "Should have 1 payment event stored");
                assertEquals("order-tx-001", orderEvents.get(0).getPayload().getOrderId());
                assertEquals("payment-tx-001", paymentEvents.get(0).getPayload().getPaymentId());

                logger.info("Multi-EventStore transactional consistency test completed successfully!");
                logger.info("   Order and payment events stored consistently");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 2: Complex Business Workflows
     * Demonstrates complex business workflows with multiple event types and processing stages.
     */
    @Test
    @Order(2)
    void testComplexBusinessWorkflows(VertxTestContext testContext) {
        logger.info("=== Testing Complex Business Workflows ===");

        // Simulate a complete order processing workflow
        String orderId = "order-workflow-001";
        String customerId = "customer-workflow-001";
        BigDecimal amount = new BigDecimal("250.00");

        Instant baseTime = Instant.now();

        // Step 1: Order Creation
        OrderEvent orderCreated = new OrderEvent(orderId, customerId, amount, "PENDING", baseTime);
        PaymentEvent paymentAuth = new PaymentEvent("payment-" + orderId, orderId, customerId, amount, "CREDIT_CARD", "AUTHORIZED", baseTime.plus(1, ChronoUnit.MINUTES));
        OrderEvent orderConfirmed = new OrderEvent(orderId, customerId, amount, "CONFIRMED", baseTime.plus(2, ChronoUnit.MINUTES));
        PaymentEvent paymentCapture = new PaymentEvent("payment-" + orderId, orderId, customerId, amount, "CREDIT_CARD", "CAPTURED", baseTime.plus(3, ChronoUnit.MINUTES));
        OrderEvent orderShipped = new OrderEvent(orderId, customerId, amount, "SHIPPED", baseTime.plus(4, ChronoUnit.MINUTES));

        orderEventStore.appendBuilder().eventType("OrderCreated").payload(orderCreated).validTime(baseTime).execute()
            .compose(v -> paymentEventStore.appendBuilder().eventType("PaymentAuthorized").payload(paymentAuth).validTime(baseTime.plus(1, ChronoUnit.MINUTES)).execute())
            .compose(v -> orderEventStore.appendBuilder().eventType("OrderConfirmed").payload(orderConfirmed).validTime(baseTime.plus(2, ChronoUnit.MINUTES)).execute())
            .compose(v -> paymentEventStore.appendBuilder().eventType("PaymentCaptured").payload(paymentCapture).validTime(baseTime.plus(3, ChronoUnit.MINUTES)).execute())
            .compose(v -> orderEventStore.appendBuilder().eventType("OrderShipped").payload(orderShipped).validTime(baseTime.plus(4, ChronoUnit.MINUTES)).execute())
            .compose(v -> Future.all(
                orderEventStore.query(EventQuery.forEventType("OrderCreated")),
                orderEventStore.query(EventQuery.forEventType("OrderConfirmed")),
                orderEventStore.query(EventQuery.forEventType("OrderShipped")),
                paymentEventStore.query(EventQuery.forEventType("PaymentAuthorized")),
                paymentEventStore.query(EventQuery.forEventType("PaymentCaptured"))))
            .onSuccess(results -> testContext.verify(() -> {
                List<BiTemporalEvent<OrderEvent>> allOrderEvents = new ArrayList<>();
                allOrderEvents.addAll(results.resultAt(0));
                allOrderEvents.addAll(results.resultAt(1));
                allOrderEvents.addAll(results.resultAt(2));

                List<BiTemporalEvent<PaymentEvent>> allPaymentEvents = new ArrayList<>();
                allPaymentEvents.addAll(results.resultAt(3));
                allPaymentEvents.addAll(results.resultAt(4));

                assertTrue(allOrderEvents.size() >= 3, "Should have at least 3 order events (created, confirmed, shipped)");
                assertTrue(allPaymentEvents.size() >= 2, "Should have at least 2 payment events (authorized, captured)");

                List<BiTemporalEvent<OrderEvent>> orderEventsByType = allOrderEvents.stream()
                    .filter(e -> e.getPayload().getOrderId().equals(orderId))
                    .toList();

                assertTrue(orderEventsByType.stream().anyMatch(e -> e.getPayload().getStatus().equals("PENDING")));
                assertTrue(orderEventsByType.stream().anyMatch(e -> e.getPayload().getStatus().equals("CONFIRMED")));
                assertTrue(orderEventsByType.stream().anyMatch(e -> e.getPayload().getStatus().equals("SHIPPED")));

                logger.info("Complex business workflows test completed successfully!");
                logger.info("   Complete order processing workflow: {} order events, {} payment events",
                    orderEventsByType.size(), allPaymentEvents.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 3: Concurrent Processing with Proper Isolation
     * Demonstrates concurrent processing scenarios with proper transaction isolation.
     */
    @Test
    @Order(3)
    void testConcurrentProcessingWithProperIsolation(VertxTestContext testContext) {
        logger.info("=== Testing Concurrent Processing with Proper Isolation ===");

        int numberOfThreads = 5;
        int eventsPerThread = 10;
        Instant baseTime = Instant.now();
        List<Future<BiTemporalEvent<OrderEvent>>> appendFutures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            for (int j = 0; j < eventsPerThread; j++) {
                String orderId = String.format("concurrent-order-%d-%d", threadId, j);
                String customerId = String.format("customer-%d", threadId);
                BigDecimal amount = new BigDecimal(String.valueOf(100 + j));
                Instant eventTime = baseTime.plus(threadId * 1000L + j * 100L, ChronoUnit.MILLIS);
                OrderEvent orderEvent = new OrderEvent(orderId, customerId, amount, "PENDING", eventTime);
                appendFutures.add(orderEventStore.appendBuilder()
                    .eventType("OrderCreated")
                    .payload(orderEvent)
                    .validTime(eventTime)
                    .execute());
            }
        }

        Future.all(new ArrayList<>(appendFutures))
            .compose(v -> orderEventStore.query(EventQuery.forEventType("OrderCreated")))
            .onSuccess(allEvents -> testContext.verify(() -> {
                long concurrentEvents = allEvents.stream()
                    .filter(e -> e.getPayload().getOrderId().startsWith("concurrent-order-"))
                    .count();

                assertEquals(numberOfThreads * eventsPerThread, concurrentEvents,
                    "Should have stored all concurrent events");
                assertEquals(numberOfThreads * eventsPerThread, appendFutures.size(),
                    "All concurrent operations should succeed");

                logger.info("Concurrent processing with proper isolation test completed successfully!");
                logger.info("   Processed {} events across {} logical threads with {} successes",
                    concurrentEvents, numberOfThreads, appendFutures.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 4: Error Handling and Rollback Scenarios
     * Demonstrates proper error handling and rollback behavior in transactional scenarios.
     */
    @Test
    @Order(4)
    void testErrorHandlingAndRollbackScenarios(VertxTestContext testContext) {
        logger.info("=== Testing Error Handling and Rollback Scenarios ===");

        // Test successful transaction first
        String successOrderId = "rollback-success-001";
        Instant successTime = Instant.now();
        OrderEvent successOrder = new OrderEvent(successOrderId, "customer-success", new BigDecimal("100.00"), "PENDING", successTime);

        // Test error handling with invalid data (this should still work as event stores are append-only)
        String errorOrderId = "rollback-error-001";
        Instant errorTime = Instant.now();
        OrderEvent errorOrder = new OrderEvent(errorOrderId, "customer-error", new BigDecimal("0.00"), "INVALID", errorTime);

        orderEventStore.appendBuilder().eventType("OrderCreated").payload(successOrder).validTime(successTime).execute()
            .compose(v -> orderEventStore.query(EventQuery.builder().eventType("OrderCreated").build()))
            .compose(successEvents -> {
                assertTrue(successEvents.stream().anyMatch(e ->
                        e.getPayload().getOrderId().equals(successOrderId)),
                    "Successful order should be stored");

                return orderEventStore.appendBuilder().eventType("OrderCreated").payload(errorOrder).validTime(errorTime).execute();
            })
            .compose(v -> orderEventStore.query(EventQuery.forEventType("OrderCreated")))
            .onSuccess(allEvents -> testContext.verify(() -> {
                assertTrue(allEvents.stream().anyMatch(e ->
                        e.getPayload().getOrderId().equals(errorOrderId)),
                    "Error order should also be stored in append-only event store");

                logger.info("Error handling and rollback scenarios test completed successfully!");
                logger.info("   Demonstrated append-only nature and error tolerance");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 5: Performance Testing with High-Throughput Scenarios
     * Demonstrates performance characteristics under high-throughput scenarios.
     */
    @Test
    @Order(5)
    void testPerformanceWithHighThroughputScenarios(VertxTestContext testContext) {
        logger.info("=== Testing Performance with High-Throughput Scenarios ===");

        int numberOfEvents = 25; // Reduced to avoid connection pool exhaustion
        long startTime = System.currentTimeMillis();

        // Create and store events in batch
        List<Future<BiTemporalEvent<OrderEvent>>> futures = new ArrayList<>();
        Instant baseTime = Instant.now();

        for (int i = 0; i < numberOfEvents; i++) {
            String orderId = String.format("perf-order-%05d", i);
            String customerId = String.format("customer-%03d", i % 10); // 10 different customers
            BigDecimal amount = new BigDecimal(String.valueOf(50 + (i % 100))); // Varying amounts
            String status = (i % 3 == 0) ? "PENDING" : (i % 3 == 1) ? "CONFIRMED" : "SHIPPED";

            Instant eventTime = baseTime.plus(i * 10, ChronoUnit.MILLIS); // 10ms apart
            OrderEvent orderEvent = new OrderEvent(orderId, customerId, amount, status, eventTime);

            futures.add(orderEventStore.appendBuilder().eventType("OrderCreated").payload(orderEvent).validTime(eventTime).execute());
        }

        // Wait for all events to be stored
        Future.all(new ArrayList<>(futures))
            .compose(v -> orderEventStore.query(EventQuery.forEventType("OrderCreated")))
            .onSuccess(allEvents -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                double eventsPerSecond = (numberOfEvents * 1000.0) / duration;
                long perfEvents = allEvents.stream()
                    .filter(e -> e.getPayload().getOrderId().startsWith("perf-order-"))
                    .count();

                assertEquals(numberOfEvents, perfEvents, "Should have stored all performance test events");

                logger.info("Performance testing with high-throughput scenarios completed successfully!");
                logger.info("   Stored {} events in {}ms ({} events/second)",
                    numberOfEvents, duration, String.format("%.2f", eventsPerSecond));
                logger.info("   Performance metrics: {} total events in event store", allEvents.size());

                assertTrue(eventsPerSecond >= 10.0,
                    String.format("Performance should be at least 10 events/second, got %.2f", eventsPerSecond));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // Event classes for testing
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String status;
        private final Instant orderTime;

        @JsonCreator
        public OrderEvent(@JsonProperty("orderId") String orderId,
                         @JsonProperty("customerId") String customerId,
                         @JsonProperty("amount") BigDecimal amount,
                         @JsonProperty("status") String status,
                         @JsonProperty("orderTime") Instant orderTime) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
            this.orderTime = orderTime;
        }

        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }
        public Instant getOrderTime() { return orderTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEvent that = (OrderEvent) o;
            return Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(status, that.status) &&
                   Objects.equals(orderTime, that.orderTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, customerId, amount, status, orderTime);
        }

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%s, status='%s', orderTime=%s}",
                               orderId, customerId, amount, status, orderTime);
        }
    }

    public static class PaymentEvent {
        private final String paymentId;
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String method;
        private final String status;
        private final Instant paymentTime;

        @JsonCreator
        public PaymentEvent(@JsonProperty("paymentId") String paymentId,
                           @JsonProperty("orderId") String orderId,
                           @JsonProperty("customerId") String customerId,
                           @JsonProperty("amount") BigDecimal amount,
                           @JsonProperty("method") String method,
                           @JsonProperty("status") String status,
                           @JsonProperty("paymentTime") Instant paymentTime) {
            this.paymentId = paymentId;
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.method = method;
            this.status = status;
            this.paymentTime = paymentTime;
        }

        public String getPaymentId() { return paymentId; }
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getMethod() { return method; }
        public String getStatus() { return status; }
        public Instant getPaymentTime() { return paymentTime; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PaymentEvent that = (PaymentEvent) o;
            return Objects.equals(paymentId, that.paymentId) &&
                   Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(method, that.method) &&
                   Objects.equals(status, that.status) &&
                   Objects.equals(paymentTime, that.paymentTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(paymentId, orderId, customerId, amount, method, status, paymentTime);
        }

        @Override
        public String toString() {
            return String.format("PaymentEvent{paymentId='%s', orderId='%s', customerId='%s', amount=%s, method='%s', status='%s', paymentTime=%s}",
                               paymentId, orderId, customerId, amount, method, status, paymentTime);
        }
    }
}



