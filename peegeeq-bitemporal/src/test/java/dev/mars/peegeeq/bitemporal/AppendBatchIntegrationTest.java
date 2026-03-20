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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
@ExtendWith(VertxExtension.class)
class AppendBatchIntegrationTest {

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private Vertx vertx;
    private final Map<String, String> originalProperties = new HashMap<>();

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

    private Future<Void> cleanupDatabase() {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool cleanupPool = PgBuilder.pool()
            .connectingTo(connectOptions)
            .build();

        return cleanupPool.withConnection(conn ->
            conn.query("SELECT to_regclass('public.bitemporal_event_log') AS table_name").execute()
                .compose(rows -> {
                    if (!rows.iterator().hasNext() || rows.iterator().next().getValue("table_name") == null) {
                        return Future.succeededFuture();
                    }
                    return conn.query("TRUNCATE TABLE bitemporal_event_log").execute().mapEmpty();
                })
        ).compose(v -> cleanupPool.close());
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;

        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.health-check.queue-checks-enabled", "false");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        cleanupDatabase()
            .compose(v -> manager.start())
            .compose(v -> {
                factory = new BiTemporalEventStoreFactory(manager);
                eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class, "bitemporal_event_log");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 30);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = Future.succeededFuture();
        if (eventStore != null) {
            closeFuture = eventStore.closeFuture();
            eventStore = null;
        }
        closeFuture
            .compose(v -> {
                if (manager != null) {
                    PeeGeeQManager m = manager;
                    manager = null;
                    return m.closeReactive();
                }
                return Future.succeededFuture();
            })
            .recover(t -> Future.succeededFuture())
            .compose(v -> cleanupDatabase())
            .onSuccess(v -> {
                restoreTestProperties();
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 30);
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


    @Test
    @DisplayName("appendBatch - successfully appends multiple events in a single batch")
    void testAppendBatch_Success(VertxTestContext testContext) throws Exception {
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

        eventStore.appendBatch(events)
            .onSuccess(results -> testContext.verify(() -> {
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
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendBatch - returns empty list for empty input")
    void testAppendBatch_EmptyList(VertxTestContext testContext) throws Exception {
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = new ArrayList<>();

        eventStore.appendBatch(events)
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertTrue(results.isEmpty());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendBatch - returns empty list for null input")
    void testAppendBatch_NullList(VertxTestContext testContext) throws Exception {
        eventStore.appendBatch(null)
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertTrue(results.isEmpty());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendBatch - handles single event batch")
    void testAppendBatch_SingleEvent(VertxTestContext testContext) throws Exception {
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

        eventStore.appendBatch(events)
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertEquals(1, results.size());
                assertEquals("single.event", results.get(0).getEventType());
                assertEquals("single-id", results.get(0).getPayload().getId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendBatch - handles large batch for throughput")
    void testAppendBatch_LargeBatch(VertxTestContext testContext) throws Exception {
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

        long startTime = System.currentTimeMillis();
        eventStore.appendBatch(events)
            .onSuccess(results -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                assertNotNull(results);
                assertEquals(batchSize, results.size());
                System.out.println("Batch of " + batchSize + " events inserted in " + duration + "ms");
                long uniqueIds = results.stream()
                    .map(BiTemporalEvent::getEventId)
                    .distinct()
                    .count();
                assertEquals(batchSize, uniqueIds);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 30);
    }

    @Test
    @DisplayName("appendBatch - events can be queried after batch insert")
    void testAppendBatch_EventsQueryable(VertxTestContext testContext) throws Exception {
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

        eventStore.appendBatch(events)
            .compose(inserted -> {
                EventQuery query = EventQuery.builder()
                    .eventType("queryable.event")
                    .build();
                return eventStore.query(query);
            })
            .onSuccess(queried -> testContext.verify(() -> {
                assertEquals(2, queried.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 10);
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
        assertEquals(eventType, batchData.eventType());
        assertEquals(payload, batchData.payload());
        assertEquals(validTime, batchData.validTime());
        assertEquals(headers, batchData.headers());
        assertEquals(correlationId, batchData.correlationId());
        assertEquals(aggregateId, batchData.aggregateId());
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
        assertNull(batchData.headers());
        assertNull(batchData.correlationId());
        assertNull(batchData.aggregateId());
    }

    private void awaitSuccess(VertxTestContext testContext, long timeoutSeconds) {
        boolean completed;
        try {
            completed = testContext.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting asynchronous test completion", e);
        }
        assertTrue(completed, "Test timed out after " + timeoutSeconds + " seconds");
        if (testContext.failed()) {
            throw new AssertionError("Asynchronous test flow failed", testContext.causeOfFailure());
        }
    }
}




