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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for appendBatch() and BatchEventData functionality.
 * Tests batch event insertion for high-throughput scenarios.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class AppendBatchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> eventStore;

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
    }

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

            cleanupPool.withConnection(conn -> 
                conn.query("DELETE FROM bitemporal_event_log").execute()
                    .onFailure(t -> {})
            ).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            cleanupPool.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
            Thread.sleep(200);
        } catch (Exception e) {
            // Cleanup failures are often expected
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanupDatabase();

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (eventStore != null) {
            try { eventStore.close(); Thread.sleep(100); } catch (Exception e) {}
            eventStore = null;
        }
        if (manager != null) {
            try { manager.stop(); Thread.sleep(200); } catch (Exception e) {}
            manager = null;
        }
        cleanupDatabase();
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    @Test
    @DisplayName("appendBatch - successfully appends multiple events in a single batch")
    void testAppendBatch_Success() throws Exception {
        // Given
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = new ArrayList<>();
        Instant validTime = Instant.now();

        for (int i = 0; i < 5; i++) {
            events.add(new PgBiTemporalEventStore.BatchEventData<>(
                "test.event",
                new TestEvent("id-" + i, "data-" + i, i),
                validTime,
                Map.of("batch", "true", "index", String.valueOf(i)),
                "correlation-" + i,
                "aggregate-" + i
            ));
        }

        // When
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(events)
            .get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(results);
        assertEquals(5, results.size());

        for (int i = 0; i < 5; i++) {
            BiTemporalEvent<TestEvent> event = results.get(i);
            assertNotNull(event.getEventId());
            assertEquals("test.event", event.getEventType());
            assertEquals("id-" + i, event.getPayload().getId());
            assertEquals("data-" + i, event.getPayload().getData());
            assertEquals(i, event.getPayload().getValue());
            assertEquals("correlation-" + i, event.getCorrelationId());
            assertEquals("aggregate-" + i, event.getAggregateId());
            assertEquals(1L, event.getVersion());
            assertFalse(event.isCorrection());
        }
    }

    @Test
    @DisplayName("appendBatch - returns empty list for empty input")
    void testAppendBatch_EmptyList() throws Exception {
        // Given
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = new ArrayList<>();

        // When
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(events)
            .get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("appendBatch - returns empty list for null input")
    void testAppendBatch_NullList() throws Exception {
        // When
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(null)
            .get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("appendBatch - handles single event batch")
    void testAppendBatch_SingleEvent() throws Exception {
        // Given
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = List.of(
            new PgBiTemporalEventStore.BatchEventData<>(
                "single.event",
                new TestEvent("single-id", "single-data", 100),
                Instant.now(),
                Map.of("single", "true"),
                "single-correlation",
                "single-aggregate"
            )
        );

        // When
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(events)
            .get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("single.event", results.get(0).getEventType());
        assertEquals("single-id", results.get(0).getPayload().getId());
    }

    @Test
    @DisplayName("appendBatch - handles large batch for throughput")
    void testAppendBatch_LargeBatch() throws Exception {
        // Given - create a larger batch to test throughput
        int batchSize = 100;
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = new ArrayList<>();
        Instant validTime = Instant.now();

        for (int i = 0; i < batchSize; i++) {
            events.add(new PgBiTemporalEventStore.BatchEventData<>(
                "bulk.event",
                new TestEvent("bulk-" + i, "bulk-data-" + i, i),
                validTime,
                Map.of("bulk", "true"),
                null,
                "bulk-aggregate"
            ));
        }

        // When
        long startTime = System.currentTimeMillis();
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(events)
            .get(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        // Then
        assertNotNull(results);
        assertEquals(batchSize, results.size());
        System.out.println("Batch of " + batchSize + " events inserted in " + duration + "ms");

        // Verify all events have unique IDs
        long uniqueIds = results.stream()
            .map(BiTemporalEvent::getEventId)
            .distinct()
            .count();
        assertEquals(batchSize, uniqueIds);
    }

    @Test
    @DisplayName("appendBatch - events can be queried after batch insert")
    void testAppendBatch_EventsQueryable() throws Exception {
        // Given
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = List.of(
            new PgBiTemporalEventStore.BatchEventData<>(
                "queryable.event",
                new TestEvent("q1", "query-data-1", 1),
                Instant.now(),
                Map.of(),
                null,
                "query-aggregate"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "queryable.event",
                new TestEvent("q2", "query-data-2", 2),
                Instant.now(),
                Map.of(),
                null,
                "query-aggregate"
            )
        );

        // When - insert batch
        eventStore.appendBatch(events).get(10, TimeUnit.SECONDS);

        // Then - query and verify
        EventQuery query = EventQuery.builder()
            .eventType("queryable.event")
            .build();
        List<BiTemporalEvent<TestEvent>> queried = eventStore.query(query)
            .get(10, TimeUnit.SECONDS);

        assertEquals(2, queried.size());
    }

    @Test
    @DisplayName("BatchEventData - constructor sets all fields correctly")
    void testBatchEventData_Constructor() {
        // Given
        String eventType = "test.type";
        TestEvent payload = new TestEvent("id", "data", 42);
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("key", "value");
        String correlationId = "corr-123";
        String aggregateId = "agg-456";

        // When
        PgBiTemporalEventStore.BatchEventData<TestEvent> batchData =
            new PgBiTemporalEventStore.BatchEventData<>(
                eventType, payload, validTime, headers, correlationId, aggregateId
            );

        // Then
        assertEquals(eventType, batchData.eventType);
        assertEquals(payload, batchData.payload);
        assertEquals(validTime, batchData.validTime);
        assertEquals(headers, batchData.headers);
        assertEquals(correlationId, batchData.correlationId);
        assertEquals(aggregateId, batchData.aggregateId);
    }

    @Test
    @DisplayName("BatchEventData - handles null optional fields")
    void testBatchEventData_NullOptionalFields() {
        // Given/When
        PgBiTemporalEventStore.BatchEventData<TestEvent> batchData =
            new PgBiTemporalEventStore.BatchEventData<>(
                "test.type",
                new TestEvent("id", "data", 1),
                Instant.now(),
                null,  // null headers
                null,  // null correlationId
                null   // null aggregateId
            );

        // Then
        assertNull(batchData.headers);
        assertNull(batchData.correlationId);
        assertNull(batchData.aggregateId);
    }
}

