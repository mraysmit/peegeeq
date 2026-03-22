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
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalence tests proving the event-bus distribution path produces
 * identical results to the direct append path.
 *
 * <p>Each test runs the same operation through both paths and compares the
 * returned event AND the database row field-by-field. This is the class of
 * tests that would have caught the original semantic gaps: regenerated
 * event IDs, regenerated transaction times, missing transaction wrappers,
 * and lost trace context.
 *
 * <p>If any future change breaks equivalence between the paths, these tests
 * will fail immediately with a clear diff showing which field diverged.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class EventBusDistributionEquivalenceTest {

    private static final Logger logger = LoggerFactory.getLogger(EventBusDistributionEquivalenceTest.class);
    private static final String TABLE_NAME = "bitemporal_event_log";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("eventbus_equiv_" + System.currentTimeMillis());
        container.withUsername("peegeeq");
        container.withPassword("peegeeq_test_password");
        return container;
    }

    public static class TestPayload {
        private final String name;
        private final int amount;

        @JsonCreator
        public TestPayload(@JsonProperty("name") String name,
                           @JsonProperty("amount") int amount) {
            this.name = name;
            this.amount = amount;
        }

        public String getName() { return name; }
        public int getAmount() { return amount; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestPayload that = (TestPayload) o;
            return amount == that.amount && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, amount);
        }

        @Override
        public String toString() {
            return "TestPayload{name='" + name + "', amount=" + amount + "}";
        }
    }

    private Vertx vertx;
    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestPayload> eventStore;
    private Pool verificationPool;

    private String resolveSchema() {
        String configured = System.getProperty("peegeeq.database.schema", "public");
        String schema = (configured == null) ? "public" : configured.trim();
        if (schema.isEmpty()) return "public";
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name for test: " + schema);
        }
        return schema;
    }

    private PgConnectOptions connectOptions() {
        return new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.health-check.enabled", "false");
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");
        System.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        System.setProperty("peegeeq.queue.recovery.enabled", "false");

        String schema = resolveSchema();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.BITEMPORAL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    verificationPool = PgBuilder.pool()
                            .with(new PoolOptions().setMaxSize(5))
                            .connectingTo(connectOptions())
                            .using(manager.getVertx())
                            .build();

                    Pool setupPool = PgBuilder.pool().connectingTo(connectOptions())
                            .using(manager.getVertx()).build();
                    return setupPool.query("TRUNCATE TABLE " + schema + "." + TABLE_NAME + " CASCADE")
                            .execute()
                            .recover(err -> Future.succeededFuture(null))
                            .compose(r -> setupPool.close());
                })
                .compose(v -> {
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(vertx, manager);
                    eventStore = (PgBiTemporalEventStore<TestPayload>) factory.createEventStore(
                            TestPayload.class, TABLE_NAME);

                    return PgBiTemporalEventStore.deployDatabaseWorkerVerticles(
                            vertx, 1, TABLE_NAME);
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        if (eventStore != null) {
            eventStore.close();
        }

        Future<Void> closeFuture = Future.succeededFuture();
        if (verificationPool != null) {
            closeFuture = closeFuture.compose(v -> verificationPool.close());
        }
        if (manager != null) {
            closeFuture = closeFuture.compose(v -> manager.closeReactive());
        }

        closeFuture
                .onSuccess(v -> {
                    System.clearProperty("peegeeq.database.host");
                    System.clearProperty("peegeeq.database.port");
                    System.clearProperty("peegeeq.database.name");
                    System.clearProperty("peegeeq.database.username");
                    System.clearProperty("peegeeq.database.password");
                    System.clearProperty("peegeeq.health-check.enabled");
                    System.clearProperty("peegeeq.health-check.queue-checks-enabled");
                    System.clearProperty("peegeeq.queue.dead-consumer-detection.enabled");
                    System.clearProperty("peegeeq.queue.recovery.enabled");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Core equivalence: returned event ID matches database row
    // ========================================================================

    @Test
    @DisplayName("Returned event ID must match DB row — direct path")
    void directPathEventIdMatchesDb(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertEventIdMatchesDatabase("equiv.eventid.direct", testContext);
    }

    @Test
    @DisplayName("Returned event ID must match DB row — event-bus path")
    void eventBusPathEventIdMatchesDb(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertEventIdMatchesDatabase("equiv.eventid.eventbus", testContext);
    }

    private void assertEventIdMatchesDatabase(String eventType,
                                               VertxTestContext testContext) {
        TestPayload payload = new TestPayload("eventid-check", 1);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    String returnedId = event.getEventId();
                    assertNotNull(returnedId, "Returned event ID must not be null");

                    return queryDbRow(eventType).map(row -> {
                        assertEquals(returnedId, row.getString("event_id"),
                                "Returned event ID must match the DB row");
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Core equivalence: returned transaction time matches database row
    // ========================================================================

    @Test
    @DisplayName("Returned transaction time must match DB row — direct path")
    void directPathTransactionTimeMatchesDb(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertTransactionTimeMatchesDatabase("equiv.txtime.direct", testContext);
    }

    @Test
    @DisplayName("Returned transaction time must match DB row — event-bus path")
    void eventBusPathTransactionTimeMatchesDb(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertTransactionTimeMatchesDatabase("equiv.txtime.eventbus", testContext);
    }

    private void assertTransactionTimeMatchesDatabase(String eventType,
                                                       VertxTestContext testContext) {
        TestPayload payload = new TestPayload("txtime-check", 2);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    Instant returnedTxTime = event.getTransactionTime();
                    assertNotNull(returnedTxTime, "Returned transaction time must not be null");

                    return queryDbRow(eventType).map(row -> {
                        Instant dbTxTime = row.getOffsetDateTime("transaction_time").toInstant();
                        assertEquals(returnedTxTime, dbTxTime,
                                "Returned transaction time must match the DB row");
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Full metadata round-trip: all fields survive both paths
    // ========================================================================

    @Test
    @DisplayName("All metadata fields round-trip correctly — direct path")
    void directPathFullMetadataRoundTrip(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertFullMetadataRoundTrip("equiv.metadata.direct", testContext);
    }

    @Test
    @DisplayName("All metadata fields round-trip correctly — event-bus path")
    void eventBusPathFullMetadataRoundTrip(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertFullMetadataRoundTrip("equiv.metadata.eventbus", testContext);
    }

    private void assertFullMetadataRoundTrip(String eventType,
                                              VertxTestContext testContext) {
        TestPayload payload = new TestPayload("full-metadata", 42);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");
        Map<String, String> headers = Map.of("source", "test-suite", "priority", "high");
        String correlationId = "corr-" + eventType;
        String causationId = "cause-" + eventType;
        String aggregateId = "agg-" + eventType;

        eventStore.append(eventType, payload, validTime, headers, correlationId, causationId, aggregateId)
                .compose(event -> {
                    // Verify returned event fields
                    assertEquals(eventType, event.getEventType(), "eventType");
                    assertEquals(validTime, event.getValidTime(), "validTime");
                    assertEquals(correlationId, event.getCorrelationId(), "correlationId");
                    assertEquals(causationId, event.getCausationId(), "causationId");
                    assertEquals(aggregateId, event.getAggregateId(), "aggregateId");
                    assertEquals(headers, event.getHeaders(), "headers");
                    assertEquals(payload, event.getPayload(), "payload");

                    // Verify DB row matches
                    return queryDbRow(eventType).map(row -> {
                        assertEquals(event.getEventId(), row.getString("event_id"),
                                "DB event_id must match returned");
                        assertEquals(eventType, row.getString("event_type"),
                                "DB event_type must match");
                        assertEquals(event.getTransactionTime(),
                                row.getOffsetDateTime("transaction_time").toInstant(),
                                "DB transaction_time must match returned");
                        assertEquals(correlationId, row.getString("correlation_id"),
                                "DB correlation_id must match");
                        assertEquals(causationId, row.getString("causation_id"),
                                "DB causation_id must match");
                        assertEquals(aggregateId, row.getString("aggregate_id"),
                                "DB aggregate_id must match");

                        Instant dbValidTime = row.getOffsetDateTime("valid_time").toInstant();
                        assertEquals(validTime, dbValidTime, "DB valid_time must match");

                        assertFalse(row.getBoolean("is_correction"), "must not be a correction");
                        assertEquals(1L, row.getLong("version"), "version must be 1");

                        logger.info("Full metadata round-trip passed for path: {}", eventType);
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Correlation ID fallback: null correlationId → falls back to eventId
    // ========================================================================

    @Test
    @DisplayName("Null correlationId falls back to eventId — direct path")
    void directPathCorrelationIdFallback(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertCorrelationIdFallback("equiv.corrfallback.direct", testContext);
    }

    @Test
    @DisplayName("Null correlationId falls back to eventId — event-bus path")
    void eventBusPathCorrelationIdFallback(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertCorrelationIdFallback("equiv.corrfallback.eventbus", testContext);
    }

    private void assertCorrelationIdFallback(String eventType,
                                              VertxTestContext testContext) {
        TestPayload payload = new TestPayload("corrfallback", 3);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");

        // Append with null correlationId — should fall back to eventId
        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    String returnedCorrelationId = event.getCorrelationId();
                    String returnedEventId = event.getEventId();

                    assertNotNull(returnedCorrelationId,
                            "Correlation ID must not be null (should fall back to eventId)");
                    assertEquals(returnedEventId, returnedCorrelationId,
                            "When correlationId is null, it must fall back to eventId");

                    return queryDbRow(eventType).map(row -> {
                        assertEquals(returnedCorrelationId, row.getString("correlation_id"),
                                "DB correlation_id must match the fallback value");
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Minimal append: no optional fields
    // ========================================================================

    @Test
    @DisplayName("Minimal append (no headers/correlationId/causationId/aggregateId) — direct path")
    void directPathMinimalAppend(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertMinimalAppend("equiv.minimal.direct", testContext);
    }

    @Test
    @DisplayName("Minimal append (no headers/correlationId/causationId/aggregateId) — event-bus path")
    void eventBusPathMinimalAppend(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertMinimalAppend("equiv.minimal.eventbus", testContext);
    }

    private void assertMinimalAppend(String eventType,
                                      VertxTestContext testContext) {
        TestPayload payload = new TestPayload("minimal", 0);
        Instant validTime = Instant.parse("2025-01-01T00:00:00Z");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    assertNotNull(event.getEventId(), "eventId must not be null");
                    assertNotNull(event.getTransactionTime(), "transactionTime must not be null");
                    assertEquals(eventType, event.getEventType(), "eventType");
                    assertEquals(validTime, event.getValidTime(), "validTime");
                    // correlationId should fall back to eventId
                    assertEquals(event.getEventId(), event.getCorrelationId(), "correlationId fallback");
                    assertNull(event.getCausationId(), "causationId must be null");
                    assertNull(event.getAggregateId(), "aggregateId must be null");

                    return queryDbRow(eventType).map(row -> {
                        assertEquals(event.getEventId(), row.getString("event_id"),
                                "DB event_id must match returned");
                        assertEquals(event.getTransactionTime(),
                                row.getOffsetDateTime("transaction_time").toInstant(),
                                "DB transaction_time must match returned");
                        assertNull(row.getString("causation_id"), "DB causation_id must be null");
                        assertNull(row.getString("aggregate_id"), "DB aggregate_id must be null");
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Valid time precision: sub-second valid times survive serialization
    // ========================================================================

    @Test
    @DisplayName("Sub-second valid time precision preserved — direct path")
    void directPathValidTimePrecision(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertValidTimePrecision("equiv.precision.direct", testContext);
    }

    @Test
    @DisplayName("Sub-second valid time precision preserved — event-bus path")
    void eventBusPathValidTimePrecision(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertValidTimePrecision("equiv.precision.eventbus", testContext);
    }

    private void assertValidTimePrecision(String eventType,
                                           VertxTestContext testContext) {
        TestPayload payload = new TestPayload("precision", 99);
        // Use a time with microsecond precision (PostgreSQL timestamptz stores microseconds)
        Instant validTime = Instant.parse("2025-06-15T12:34:56.789012Z");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    assertEquals(validTime, event.getValidTime(),
                            "Returned validTime must exactly match input");

                    return queryDbRow(eventType).map(row -> {
                        Instant dbValidTime = row.getOffsetDateTime("valid_time").toInstant();
                        assertEquals(validTime, dbValidTime,
                                "DB valid_time must preserve sub-second precision");
                        return event;
                    });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Trace context propagation: both paths must operate under trace
    // ========================================================================

    @Test
    @DisplayName("Append succeeds with active trace context — direct path")
    void directPathWithTraceContext(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertAppendWithTrace("equiv.trace.direct", testContext);
    }

    @Test
    @DisplayName("Append succeeds with active trace context — event-bus path")
    void eventBusPathWithTraceContext(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertAppendWithTrace("equiv.trace.eventbus", testContext);
    }

    private void assertAppendWithTrace(String eventType,
                                        VertxTestContext testContext) {
        TestPayload payload = new TestPayload("trace-test", 7);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");

        String callerTraceId = "aabbccdd11223344aabbccdd11223344";
        String callerSpanId = "1122334455667788";
        MDC.put(TraceContextUtil.MDC_TRACE_ID, callerTraceId);
        MDC.put(TraceContextUtil.MDC_SPAN_ID, callerSpanId);

        try {
            eventStore.append(eventType, payload, validTime)
                    .compose(event -> {
                        assertNotNull(event.getEventId(), "Event must be stored");

                        return queryDbRow(eventType).map(row -> {
                            assertEquals(event.getEventId(), row.getString("event_id"),
                                    "DB event_id must match returned");
                            assertEquals(event.getTransactionTime(),
                                    row.getOffsetDateTime("transaction_time").toInstant(),
                                    "DB transaction_time must match returned");
                            return event;
                        });
                    })
                    .onSuccess(e -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } finally {
            MDC.remove(TraceContextUtil.MDC_TRACE_ID);
            MDC.remove(TraceContextUtil.MDC_SPAN_ID);
        }

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Multiple sequential appends: both paths handle ordering
    // ========================================================================

    @Test
    @DisplayName("Sequential appends produce distinct events with correct metadata — direct path")
    void directPathSequentialAppends(VertxTestContext testContext) throws Exception {
        System.clearProperty("peegeeq.database.use.event.bus.distribution");
        assertSequentialAppends("equiv.seq.direct", testContext);
    }

    @Test
    @DisplayName("Sequential appends produce distinct events with correct metadata — event-bus path")
    void eventBusPathSequentialAppends(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
        assertSequentialAppends("equiv.seq.eventbus", testContext);
    }

    private void assertSequentialAppends(String eventTypePrefix,
                                          VertxTestContext testContext) {
        String type1 = eventTypePrefix + ".first";
        String type2 = eventTypePrefix + ".second";
        TestPayload payload1 = new TestPayload("first", 1);
        TestPayload payload2 = new TestPayload("second", 2);
        Instant validTime = Instant.parse("2025-06-15T12:00:00Z");

        eventStore.append(type1, payload1, validTime)
                .compose(event1 -> {
                    assertNotNull(event1.getEventId());

                    return eventStore.append(type2, payload2, validTime)
                            .compose(event2 -> {
                                assertNotNull(event2.getEventId());
                                assertNotEquals(event1.getEventId(), event2.getEventId(),
                                        "Sequential appends must produce distinct event IDs");

                                // Verify both exist in DB with correct metadata
                                String sql = "SELECT event_id, event_type, transaction_time " +
                                        "FROM " + resolveSchema() + "." + TABLE_NAME +
                                        " WHERE event_type IN ($1, $2) ORDER BY event_type";
                                return verificationPool.preparedQuery(sql)
                                        .execute(Tuple.of(type1, type2))
                                        .map(rows -> {
                                            int count = 0;
                                            for (Row row : rows) {
                                                count++;
                                                String dbEventType = row.getString("event_type");
                                                String dbEventId = row.getString("event_id");
                                                Instant dbTxTime = row.getOffsetDateTime("transaction_time")
                                                        .toInstant();

                                                if (dbEventType.equals(type1)) {
                                                    assertEquals(event1.getEventId(), dbEventId,
                                                            "First event: DB event_id must match");
                                                    assertEquals(event1.getTransactionTime(), dbTxTime,
                                                            "First event: DB transaction_time must match");
                                                } else {
                                                    assertEquals(event2.getEventId(), dbEventId,
                                                            "Second event: DB event_id must match");
                                                    assertEquals(event2.getTransactionTime(), dbTxTime,
                                                            "Second event: DB transaction_time must match");
                                                }
                                            }
                                            assertEquals(2, count,
                                                    "Both events must be persisted");
                                            return event2;
                                        });
                            });
                })
                .onSuccess(e -> testContext.completeNow())
                .onFailure(testContext::failNow);

        awaitAndRethrow(testContext);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Query the single DB row for a given event type.
     * Fails if zero or multiple rows exist.
     */
    private Future<Row> queryDbRow(String eventType) {
        String sql = "SELECT * FROM " + resolveSchema() + "." + TABLE_NAME
                + " WHERE event_type = $1";
        return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                .map(rows -> {
                    assertEquals(1, rows.size(),
                            "Expected exactly 1 row for event_type=" + eventType);
                    return rows.iterator().next();
                });
    }

    private static void awaitAndRethrow(VertxTestContext testContext) {
        try {
            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Test did not complete within 30 seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted");
        }
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
