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
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PgBiTemporalEventStore.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class PgBiTemporalEventStoreTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private EventStore<TestEvent> eventStore;
    
    /**
     * Test event class.
     */
    public static class TestEvent {
        private final String id;
        private final String data;
        private final int value;
        
        @JsonCreator
        public TestEvent(@JsonProperty("id") String id,
                        @JsonProperty("data") String data,
                        @JsonProperty("value") int value) {
            this.id = id;
            this.data = data;
            this.value = value;
        }
        
        public String getId() { return id; }
        public String getData() { return data; }
        public int getValue() { return value; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestEvent testEvent = (TestEvent) o;
            return value == testEvent.value &&
                   Objects.equals(id, testEvent.id) &&
                   Objects.equals(data, testEvent.data);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
        
        @Override
        public String toString() {
            return "TestEvent{" +
                    "id='" + id + '\'' +
                    ", data='" + data + '\'' +
                    ", value=" + value +
                    '}';
        }
    }

    /**
     * Helper method to clean up the database before and after tests.
     * This ensures proper test isolation by removing all events.
     * Uses robust cleanup with verification to prevent race conditions.
     */
    private void cleanupDatabase() {
        try {
            PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

            Pool cleanupPool = PgBuilder.pool()
                .connectingTo(connectOptions)
                .build();

            // Perform robust cleanup with verification
            cleanupPool.withConnection(conn -> {
                // First, delete all events
                return conn.query("DELETE FROM bitemporal_event_log").execute()
                    .compose(deleteResult -> {
                        // Verify cleanup was successful by counting remaining rows
                        return conn.query("SELECT COUNT(*) as count FROM bitemporal_event_log").execute();
                    })
                    .map(countResult -> {
                        int remainingRows = countResult.iterator().next().getInteger("count");
                        if (remainingRows > 0) {
                            System.out.println("WARNING: Database cleanup incomplete - " + remainingRows + " rows remaining");
                        }
                        return null;
                    })
                    .onFailure(throwable -> {
                        // Table might not exist yet, which is fine for new test runs
                        if (!throwable.getMessage().contains("does not exist")) {
                            System.out.println("Cleanup operation warning: " + throwable.getMessage());
                        }
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            cleanupPool.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);

            // Additional wait to ensure all async operations complete
            Thread.sleep(200);

        } catch (Exception e) {
            // Cleanup failures are often expected (table doesn't exist yet)
            String message = e.getMessage();
            if (message == null || !message.contains("does not exist")) {
                System.out.println("Database cleanup info: " + e.getClass().getSimpleName() + " - " +
                    (message != null ? message : "No message available"));
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Clean database before starting each test to ensure isolation
        cleanupDatabase();

        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Disable queue health checks since we only have bitemporal_event_log table
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Initialize database schema using centralized schema initializer
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and event store
        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(TestEvent.class);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        // Close event store first to stop any ongoing operations
        if (eventStore != null) {
            try {
                eventStore.close();
                // Wait a bit for connections to close
                Thread.sleep(100);
            } catch (Exception e) {
                System.out.println("Event store cleanup warning: " + e.getMessage());
            }
            eventStore = null;
        }

        // Stop manager to close all connections
        if (manager != null) {
            try {
                manager.stop();
                // Wait for manager to fully stop
                Thread.sleep(200);
            } catch (Exception e) {
                System.out.println("Manager stop warning: " + e.getMessage());
            }
            manager = null;
        }

        // Clean up database tables to ensure test isolation
        cleanupDatabase();

        // Additional wait to ensure cleanup is complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }
    
    @Test
    void testAppendEvent() throws Exception {
        // Given
        TestEvent payload = new TestEvent("test-1", "test data", 42);
        Instant validTime = Instant.now();
        
        // When
        BiTemporalEvent<TestEvent> event = eventStore.append("TestEvent", payload, validTime).join();
        
        // Then
        assertNotNull(event);
        assertNotNull(event.getEventId());
        assertEquals("TestEvent", event.getEventType());
        assertEquals(payload, event.getPayload());
        assertEquals(validTime, event.getValidTime());
        assertNotNull(event.getTransactionTime());
        assertEquals(1L, event.getVersion());
        assertNull(event.getPreviousVersionId());
        assertFalse(event.isCorrection());
        assertNull(event.getCorrectionReason());
    }
    
    @Test
    void testAppendEventWithMetadata() throws Exception {
        // Given
        TestEvent payload = new TestEvent("test-2", "test data", 100);
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("source", "test", "version", "1.0");
        String correlationId = "corr-123";
        String aggregateId = "agg-456";
        
        // When
        BiTemporalEvent<TestEvent> event = eventStore.append(
            "TestEvent", payload, validTime, headers, correlationId, aggregateId
        ).join();
        
        // Then
        assertNotNull(event);
        assertEquals("TestEvent", event.getEventType());
        assertEquals(payload, event.getPayload());
        assertEquals(headers, event.getHeaders());
        assertEquals(correlationId, event.getCorrelationId());
        assertEquals(aggregateId, event.getAggregateId());
    }
    
    @Test
    void testAppendCorrection() throws Exception {
        // Given - append original event
        TestEvent originalPayload = new TestEvent("test-3", "original data", 50);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> originalEvent = eventStore.append(
            "TestEvent", originalPayload, validTime
        ).join();
        
        // When - append correction
        TestEvent correctedPayload = new TestEvent("test-3", "corrected data", 75);
        String correctionReason = "Data correction";
        
        BiTemporalEvent<TestEvent> correctionEvent = eventStore.appendCorrection(
            originalEvent.getEventId(), "TestEvent", correctedPayload, validTime, correctionReason
        ).join();
        
        // Then
        assertNotNull(correctionEvent);
        assertNotEquals(originalEvent.getEventId(), correctionEvent.getEventId());
        assertEquals("TestEvent", correctionEvent.getEventType());
        assertEquals(correctedPayload, correctionEvent.getPayload());
        assertEquals(2L, correctionEvent.getVersion());
        assertEquals(originalEvent.getEventId(), correctionEvent.getPreviousVersionId());
        assertTrue(correctionEvent.isCorrection());
        assertEquals(correctionReason, correctionEvent.getCorrectionReason());
    }
    
    @Test
    void testQueryAllEvents() throws Exception {
        // Given
        TestEvent event1 = new TestEvent("test-4", "data 1", 10);
        TestEvent event2 = new TestEvent("test-5", "data 2", 20);

        Instant validTime1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant validTime2 = Instant.now();

        // Use unique event type to avoid contamination from other tests
        String uniqueEventType = "IntegrationTestEvent";
        eventStore.append(uniqueEventType, event1, validTime1).join();
        eventStore.append(uniqueEventType, event2, validTime2).join();

        // When - Query only our unique event type to avoid contamination
        List<BiTemporalEvent<TestEvent>> events = eventStore.query(
            EventQuery.forEventType(uniqueEventType)
        ).join();

        // Then
        assertEquals(2, events.size());
    }
    
    @Test
    void testQueryByEventType() throws Exception {
        // Given
        TestEvent event1 = new TestEvent("test-6", "data 1", 30);
        TestEvent event2 = new TestEvent("test-7", "data 2", 40);

        Instant validTime = Instant.now();

        // Use unique event types to avoid contamination from other tests
        String uniqueEventType1 = "IntegrationTestEventType1";
        String uniqueEventType2 = "IntegrationTestEventType2";
        eventStore.append(uniqueEventType1, event1, validTime).join();
        eventStore.append(uniqueEventType2, event2, validTime).join();

        // When
        List<BiTemporalEvent<TestEvent>> events = eventStore.query(
            EventQuery.forEventType(uniqueEventType1)
        ).join();

        // Then
        assertEquals(1, events.size());
        assertEquals(uniqueEventType1, events.get(0).getEventType());
        assertEquals(event1, events.get(0).getPayload());
    }
    
    @Test
    void testQueryByAggregate() throws Exception {
        // Given
        TestEvent event1 = new TestEvent("test-8", "data 1", 60);
        TestEvent event2 = new TestEvent("test-9", "data 2", 70);

        Instant validTime = Instant.now();

        eventStore.append("TestEvent", event1, validTime, Map.of(), "corr-1", "agg-1").join();
        eventStore.append("TestEvent", event2, validTime, Map.of(), "corr-2", "agg-2").join();

        // When
        List<BiTemporalEvent<TestEvent>> events = eventStore.query(
            EventQuery.forAggregate("agg-1")
        ).join();

        // Then
        assertEquals(1, events.size());
        assertEquals("agg-1", events.get(0).getAggregateId());
        assertEquals(event1, events.get(0).getPayload());
    }

    @Test
    void testQueryByAggregateAndEventType() throws Exception {
        // Given - Create events with different aggregates and types
        TestEvent event1 = new TestEvent("test-agg-type-1", "data 1", 100);
        TestEvent event2 = new TestEvent("test-agg-type-2", "data 2", 200);
        TestEvent event3 = new TestEvent("test-agg-type-3", "data 3", 300);

        Instant validTime = Instant.now();

        // Same aggregate, different types
        eventStore.append("TypeA", event1, validTime, Map.of(), "corr-1", "agg-1").join();
        eventStore.append("TypeB", event2, validTime, Map.of(), "corr-2", "agg-1").join();

        // Different aggregate, same type as first
        eventStore.append("TypeA", event3, validTime, Map.of(), "corr-3", "agg-2").join();

        // When - Query for specific aggregate AND specific type using builder
        List<BiTemporalEvent<TestEvent>> events = eventStore.query(
            EventQuery.builder()
                .aggregateId("agg-1")
                .eventType("TypeA")
                .build()
        ).join();

        // Then - Should only get the one event matching BOTH criteria
        assertEquals(1, events.size());
        assertEquals("agg-1", events.get(0).getAggregateId());
        assertEquals("TypeA", events.get(0).getEventType());
        assertEquals(event1, events.get(0).getPayload());
    }

    @Test
    void testQueryByAggregateAndEventTypeConvenienceMethod() throws Exception {
        // Given - Create events with different aggregates and types
        TestEvent event1 = new TestEvent("test-convenience-1", "data 1", 100);
        TestEvent event2 = new TestEvent("test-convenience-2", "data 2", 200);
        TestEvent event3 = new TestEvent("test-convenience-3", "data 3", 300);

        Instant validTime = Instant.now();

        // Same aggregate, different types
        eventStore.append("OrderCreated", event1, validTime, Map.of(), "corr-1", "order-123").join();
        eventStore.append("OrderUpdated", event2, validTime, Map.of(), "corr-2", "order-123").join();

        // Different aggregate, same type as first
        eventStore.append("OrderCreated", event3, validTime, Map.of(), "corr-3", "order-456").join();

        // When - Query using the convenience method
        List<BiTemporalEvent<TestEvent>> events = eventStore.query(
            EventQuery.forAggregateAndType("order-123", "OrderCreated")
        ).join();

        // Then - Should only get the one event matching BOTH criteria
        assertEquals(1, events.size());
        assertEquals("order-123", events.get(0).getAggregateId());
        assertEquals("OrderCreated", events.get(0).getEventType());
        assertEquals(event1, events.get(0).getPayload());
    }

    @Test
    void testGetById() throws Exception {
        // Given
        TestEvent payload = new TestEvent("test-10", "test data", 80);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> originalEvent = eventStore.append(
            "TestEvent", payload, validTime
        ).join();
        
        // When
        BiTemporalEvent<TestEvent> retrievedEvent = eventStore.getById(originalEvent.getEventId()).join();
        
        // Then
        assertNotNull(retrievedEvent);
        assertEquals(originalEvent.getEventId(), retrievedEvent.getEventId());
        assertEquals(originalEvent.getPayload(), retrievedEvent.getPayload());
    }
    
    @Test
    void testGetAllVersions() throws Exception {
        // Given
        TestEvent originalPayload = new TestEvent("test-11", "original", 90);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> originalEvent = eventStore.append(
            "TestEvent", originalPayload, validTime
        ).join();
        
        TestEvent correctedPayload = new TestEvent("test-11", "corrected", 95);
        eventStore.appendCorrection(
            originalEvent.getEventId(), "TestEvent", correctedPayload, validTime, "Correction"
        ).join();
        
        // When
        List<BiTemporalEvent<TestEvent>> versions = eventStore.getAllVersions(originalEvent.getEventId()).join();
        
        // Then
        assertEquals(2, versions.size());
        assertEquals(1L, versions.get(0).getVersion());
        assertEquals(2L, versions.get(1).getVersion());
        assertFalse(versions.get(0).isCorrection());
        assertTrue(versions.get(1).isCorrection());
    }
    
    @Test
    void testGetStats() throws Exception {
        // Given
        TestEvent event1 = new TestEvent("test-12", "data 1", 100);
        TestEvent event2 = new TestEvent("test-13", "data 2", 200);
        
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> originalEvent = eventStore.append("TestEvent", event1, validTime).join();
        eventStore.append("OtherEvent", event2, validTime).join();
        eventStore.appendCorrection(originalEvent.getEventId(), "TestEvent", event1, validTime, "Test correction").join();
        
        // When
        EventStore.EventStoreStats stats = eventStore.getStats().join();
        
        // Then
        assertEquals(3, stats.getTotalEvents());
        assertEquals(1, stats.getTotalCorrections());
        assertTrue(stats.getEventCountsByType().containsKey("TestEvent"));
        assertTrue(stats.getEventCountsByType().containsKey("OtherEvent"));
        assertEquals(2L, stats.getEventCountsByType().get("TestEvent").longValue());
        assertEquals(1L, stats.getEventCountsByType().get("OtherEvent").longValue());
        assertNotNull(stats.getOldestEventTime());
        assertNotNull(stats.getNewestEventTime());
        assertTrue(stats.getStorageSizeBytes() > 0);
    }
}
