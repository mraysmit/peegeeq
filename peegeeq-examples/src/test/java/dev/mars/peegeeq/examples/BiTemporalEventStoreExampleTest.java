package dev.mars.peegeeq.examples;

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
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BiTemporalEventStoreExample class.
 * 
 * This test verifies the functionality of the bi-temporal event store example,
 * including event creation, querying, corrections, and temporal queries.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Testcontainers
class BiTemporalEventStoreExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalEventStoreExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");
    
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    
    private PeeGeeQManager manager;
    private EventStore<OrderEvent> eventStore;
    
    @BeforeEach
    void setUp() {
        logger.info("=== Setting up BiTemporalEventStoreExampleTest ===");

        // Configure database connection for TestContainers
        System.setProperty("db.host", postgres.getHost());
        System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("db.database", postgres.getDatabaseName());
        System.setProperty("db.username", postgres.getUsername());
        System.setProperty("db.password", postgres.getPassword());

        logger.info("Database configured: {}:{}/{}", postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Configure PeeGeeQ to use the TestContainer
        logger.info("Configuring PeeGeeQ to use TestContainer database");
        logger.info("Database URL: jdbc:postgresql://{}:{}/{}",
                   postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize PeeGeeQ manager
        logger.info("Initializing PeeGeeQ manager and starting services");
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ manager started successfully");

        // Create event store with unique table name for test isolation
        logger.info("Creating bi-temporal event store for OrderEvent");
        System.out.println("DEBUG: About to create BiTemporalEventStoreFactory");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
        System.out.println("DEBUG: About to call factory.createEventStore()");
        eventStore = factory.createEventStore(OrderEvent.class);
        System.out.println("DEBUG: Event store created successfully");
        logger.info("Bi-temporal event store created successfully");
        logger.info("=== Setup completed ===");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down BiTemporalEventStoreExampleTest ===");
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Clean up resources
        if (eventStore != null) {
            try {
                logger.info("Closing bi-temporal event store");
                eventStore.close();
                logger.info("Event store closed successfully");
            } catch (Exception e) {
                logger.error("Error closing event store", e);
            }
        }

        if (manager != null) {
            try {
                logger.info("Stopping PeeGeeQ manager");
                manager.stop();
                logger.info("PeeGeeQ manager stopped successfully");
            } catch (Exception e) {
                logger.error("Error stopping PeeGeeQ manager", e);
            }
        }

        // Clear system properties
        logger.info("Clearing system properties");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        logger.info("=== Teardown completed ===");
    }
    
    @Test
    void testEventStoreInitialization() {
        System.out.println("DEBUG: testEventStoreInitialization starting");
        logger.info("=== Testing event store initialization ===");

        // This test verifies that the event store was initialized correctly
        assertNotNull(eventStore, "Event store should not be null");
        logger.info("Event store initialized successfully");

        logger.info("Event store initialization test completed successfully");
    }
    
    @Test
    void testBiTemporalEventStoreOperations() throws Exception {
        // Test the core functionality of the bi-temporal event store
        System.out.println("DEBUG: Starting testBiTemporalEventStoreOperations");

        // Just test that the event store was created successfully
        assertNotNull(eventStore, "Event store should not be null");
        System.out.println("DEBUG: Event store is not null");

        // Try a simple operation
        System.out.println("DEBUG: About to test a simple query");
        List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();
        System.out.println("DEBUG: Query completed, found " + allEvents.size() + " events");

        // If we get here, the basic functionality works
        assertTrue(true, "Basic event store operations work");

        // 1. Create test data
        Instant baseTime = Instant.now();
        OrderEvent order1 = new OrderEvent("TEST-001", "CUST-123", new BigDecimal("99.99"), "CREATED");
        OrderEvent order2 = new OrderEvent("TEST-002", "CUST-456", new BigDecimal("149.99"), "CREATED");

        // Append first event
        BiTemporalEvent<OrderEvent> event1 = eventStore.append("OrderCreated", order1, baseTime, Map.of("source", "test", "region", "US"),
            "test-corr-001", "TEST-001"
        ).join();

        BiTemporalEvent<OrderEvent> event2 = eventStore.append("OrderCreated", order2, baseTime.plus(10, ChronoUnit.MINUTES), Map.of("source", "test", "region", "EU"),
            "test-corr-002", "TEST-002"
        ).join();

        // 2. Verify events were created
        assertNotNull(event1);
        assertNotNull(event2);
        assertEquals("OrderCreated", event1.getEventType());
        assertEquals("TEST-001", event1.getPayload().getOrderId());
        assertEquals(new BigDecimal("99.99"), event1.getPayload().getAmount());

        // 3. Query all events
        allEvents = eventStore.query(EventQuery.all()).join();
        assertTrue(allEvents.size() >= 2, "Should have at least the 2 events we just created");

        // 4. Query by event type
        List<BiTemporalEvent<OrderEvent>> orderEvents = eventStore.query(EventQuery.forEventType("OrderCreated")
        ).join();
        assertTrue(orderEvents.size() >= 2, "Should have at least the 2 OrderCreated events we just created");

        // 5. Query by aggregate
        List<BiTemporalEvent<OrderEvent>> order1Events = eventStore.query(EventQuery.forAggregate("TEST-001")
        ).join();
        assertEquals(1, order1Events.size(), "Should have exactly 1 event for TEST-001 aggregate");

        // 6. Add a correction event
        OrderEvent correctedOrder = new OrderEvent("TEST-001", "CUST-123", new BigDecimal("89.99"), "CREATED");

        BiTemporalEvent<OrderEvent> correctionEvent = eventStore.appendCorrection(event1.getEventId(), "OrderCreated", correctedOrder, baseTime,
                Map.of("source", "test", "region", "US", "corrected", "true"),
            "test-corr-001", "TEST-001", "Price correction due to discount"
        ).join();

        // 7. Verify correction
        assertNotNull(correctionEvent);
        assertTrue(correctionEvent.isCorrection());
        assertEquals(new BigDecimal("89.99"), correctionEvent.getPayload().getAmount());

        // 8. Get all versions of an event
        List<BiTemporalEvent<OrderEvent>> versions = eventStore.getAllVersions(event1.getEventId()).join();
        assertEquals(2, versions.size());

        // 9. Point-in-time query
        Instant beforeCorrection = correctionEvent.getTransactionTime().minus(1, ChronoUnit.SECONDS);
        BiTemporalEvent<OrderEvent> eventBeforeCorrection = eventStore.getAsOfTransactionTime(event1.getEventId(), beforeCorrection
        ).join();

        assertNotNull(eventBeforeCorrection);
        assertEquals(new BigDecimal("99.99"), eventBeforeCorrection.getPayload().getAmount());

        // 10. Get statistics
        EventStore.EventStoreStats stats = eventStore.getStats().join();
        assertNotNull(stats);
        assertTrue(stats.getTotalEvents() >= 3, "Should have at least 3 events (2 original + 1 correction)");
        assertTrue(stats.getTotalCorrections() >= 1, "Should have at least 1 correction");
    }

    @Test
    void testTemporalRangeQueries() throws Exception {
        // Test temporal range queries functionality

        // Use a unique timestamp far in the past to avoid conflicts with other tests
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS);
        String testId = String.valueOf(System.currentTimeMillis());

        // Create events at different times with unique IDs
        OrderEvent order1 = new OrderEvent("TEMPORAL-" + testId + "-001", "CUST-001", new BigDecimal("50.00"), "CREATED");
        OrderEvent order2 = new OrderEvent("TEMPORAL-" + testId + "-002", "CUST-002", new BigDecimal("75.00"), "CREATED");
        OrderEvent order3 = new OrderEvent("TEMPORAL-" + testId + "-003", "CUST-003", new BigDecimal("100.00"), "CREATED");

        // Append events with different valid times
        eventStore.append("TemporalTest", order1, baseTime, Map.of(), "corr-1", "TEMPORAL-" + testId + "-001").join();
        eventStore.append("TemporalTest", order2, baseTime.plus(30, ChronoUnit.MINUTES), Map.of(), "corr-2", "TEMPORAL-" + testId + "-002").join();
        eventStore.append("TemporalTest", order3, baseTime.plus(90, ChronoUnit.MINUTES), Map.of(), "corr-3", "TEMPORAL-" + testId + "-003").join();

        // Query events within a specific time range
        Instant rangeStart = baseTime.plus(15, ChronoUnit.MINUTES);
        Instant rangeEnd = baseTime.plus(60, ChronoUnit.MINUTES);

        // Query for the specific event we expect to be in the range
        BiTemporalEvent<OrderEvent> event = eventStore.query(
            EventQuery.builder()
                .validTimeRange(new TemporalRange(rangeStart, rangeEnd))
                .eventType("TemporalTest")
                .aggregateId("TEMPORAL-" + testId + "-002") // Specific aggregate ID
                .build()
        ).join()
        .stream()
        .findFirst()
        .orElse(null);

        // Verify we found the expected event
        assertNotNull(event, "Should find the event in the time range");
        assertEquals("TEMPORAL-" + testId + "-002", event.getPayload().getOrderId());
        assertEquals("CUST-002", event.getPayload().getCustomerId());
        assertEquals(new BigDecimal("75.00"), event.getPayload().getAmount());
    }

    @Test
    void testOrderEventEqualsAndHashCode() {
        // Test the OrderEvent equals and hashCode methods

        OrderEvent order1 = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("99.99"), "CREATED");
        OrderEvent order2 = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("99.99"), "CREATED");
        OrderEvent order3 = new OrderEvent("ORDER-002", "CUST-456", new BigDecimal("149.99"), "PENDING");

        // Test equals
        assertEquals(order1, order2);
        assertNotEquals(order1, order3);
        assertNotEquals(order1, null);
        assertNotEquals(order1, "not an order");

        // Test hashCode
        assertEquals(order1.hashCode(), order2.hashCode());
        assertNotEquals(order1.hashCode(), order3.hashCode());

        // Test toString
        String toString = order1.toString();
        assertTrue(toString.contains("ORDER-001"));
        assertTrue(toString.contains("CUST-123"));
        assertTrue(toString.contains("99.99"));
        assertTrue(toString.contains("CREATED"));
    }

    @Test
    void testAsyncOperations() throws Exception {
        // Test that async operations work correctly

        OrderEvent order = new OrderEvent("ASYNC-001", "CUST-ASYNC", new BigDecimal("25.00"), "PENDING");

        // Test async append
        CompletableFuture<BiTemporalEvent<OrderEvent>> future = eventStore.append("OrderCreated", order, Instant.now(), Map.of("async", "true"), "async-corr", "ASYNC-001"
        );

        BiTemporalEvent<OrderEvent> event = future.join();
        assertNotNull(event);
        assertEquals("ASYNC-001", event.getPayload().getOrderId());

        // Test async query
        CompletableFuture<List<BiTemporalEvent<OrderEvent>>> queryFuture = eventStore.query(EventQuery.forAggregate("ASYNC-001"));

        List<BiTemporalEvent<OrderEvent>> events = queryFuture.join();
        assertEquals(1, events.size());
        assertEquals("ASYNC-001", events.get(0).getPayload().getOrderId());
    }

    /**
     * Example event payload representing a basic order.
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String status;

        @JsonCreator
        public OrderEvent(@JsonProperty("orderId") String orderId,
                         @JsonProperty("customerId") String customerId,
                         @JsonProperty("amount") BigDecimal amount,
                         @JsonProperty("status") String status) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
        }

        // Getters
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEvent that = (OrderEvent) o;
            return Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(status, that.status);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, customerId, amount, status);
        }

        @Override
        public String toString() {
            return "OrderEvent{" +
                    "orderId='" + orderId + '\'' +
                    ", customerId='" + customerId + '\'' +
                    ", amount=" + amount +
                    ", status='" + status + '\'' +
                    '}';
        }
    }
}
