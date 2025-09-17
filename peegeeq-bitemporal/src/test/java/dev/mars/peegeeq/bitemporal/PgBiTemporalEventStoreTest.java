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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
@Testcontainers
class PgBiTemporalEventStoreTest extends BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStoreTest.class);

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
    @Override
    protected void cleanupDatabase() {
        try {
            PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(getPostgres().getHost())
                .setPort(getPostgres().getFirstMappedPort())
                .setDatabase(getPostgres().getDatabaseName())
                .setUser(getPostgres().getUsername())
                .setPassword(getPostgres().getPassword());

            Pool cleanupPool = PgBuilder.pool()
                .connectingTo(connectOptions)
                .build();

            // Use Vert.x 5 .await() for synchronous execution - clean up only bitemporal tables
            try {
                cleanupPool.query("TRUNCATE TABLE bitemporal_event_log CASCADE").execute().await();
                logger.debug("✅ Truncated bitemporal_event_log table successfully");
            } catch (Exception e) {
                logger.debug("Could not truncate bitemporal_event_log (may not exist yet): {}", e.getMessage());
            }

            cleanupPool.close().await();
            logger.debug("Database cleanup completed successfully");

        } catch (Exception e) {
            // Tables might not exist yet, which is fine
            logger.debug("Could not perform database cleanup (this may be expected during first test): {}", e.getMessage());
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Call parent setup which handles shared container and PeeGeeQ initialization
        super.setUp();

        // Get the initialized manager and factory from parent
        this.manager = super.manager;
        this.factory = super.factory;

        // Create our specific event store
        this.eventStore = factory.createEventStore(TestEvent.class);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Close our specific event store
        if (eventStore != null) {
            try {
                eventStore.close();
                Thread.sleep(100);
            } catch (Exception e) {
                System.out.println("Event store cleanup warning: " + e.getMessage());
            }
            eventStore = null;
        }

        // Call parent teardown which handles manager and database cleanup
        super.tearDown();
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
