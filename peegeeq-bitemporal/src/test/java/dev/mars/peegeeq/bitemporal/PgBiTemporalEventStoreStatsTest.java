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
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link PgBiTemporalEventStore#getStats()}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Empty store returns correct zero/null stats</li>
 *   <li>Timestamp fidelity: events stored with offset-aware valid_time are returned
 *       with correct timezone semantics (no silent UTC assumption from LocalDateTime)</li>
 *   <li>Multiple events: oldest/newest tracked correctly</li>
 *   <li>Corrections counted correctly</li>
 *   <li>Event counts by type aggregated correctly</li>
 *   <li>Unique aggregate count</li>
 *   <li>Closed store returns failed future</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgBiTemporalEventStoreStatsTest {

    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_stats_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<StatsTestEvent> eventStore;
    private Vertx vertx;

    public static class StatsTestEvent {
        private final String id;
        private final String data;
        private final int value;

        @JsonCreator
        public StatsTestEvent(@JsonProperty("id") String id,
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
            StatsTestEvent that = (StatsTestEvent) o;
            return value == that.value && Objects.equals(id, that.id) && Objects.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
    }

    private String resolveSchema() {
        String configured = System.getProperty(DATABASE_SCHEMA_PROPERTY, "public");
        String schema = configured == null ? "public" : configured.trim();
        if (schema.isEmpty()) {
            return "public";
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name for test: " + schema);
        }
        return schema;
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
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

        vertx = Vertx.vertx();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
        Pool setupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        setupPool.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
                .recover(err -> Future.succeededFuture(null))
                .compose(v -> setupPool.close())
                .compose(v -> manager.start())
                .onSuccess(v -> {
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(vertx, manager);
                    eventStore = (PgBiTemporalEventStore<StatsTestEvent>)
                            factory.createEventStore(StatsTestEvent.class, "bitemporal_event_log");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = (eventStore != null)
                ? eventStore.close()
                : Future.succeededFuture();

        closeFuture
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .compose(v -> vertx != null ? vertx.close() : Future.succeededFuture())
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

    // ==================== Positive Tests ====================

    @Test
    void testGetStatsEmptyStoreReturnsZeroes(VertxTestContext testContext) throws Exception {
        eventStore.getStats()
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(0, stats.getTotalEvents());
                    assertEquals(0, stats.getTotalCorrections());
                    assertNull(stats.getOldestEventTime());
                    assertNull(stats.getNewestEventTime());
                    assertTrue(stats.getEventCountsByType().isEmpty());
                    assertTrue(stats.getStorageSizeBytes() >= 0);
                    assertEquals(0, stats.getUniqueAggregateCount());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsTimestampPreservesUtcOffset(VertxTestContext testContext) throws Exception {
        // Store an event with a known UTC instant
        Instant knownTime = Instant.parse("2025-06-15T14:30:00Z");

        eventStore.appendBuilder()
                .eventType("TimestampTest")
                .payload(new StatsTestEvent("ts1", "utc-check", 1))
                .validTime(knownTime)
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertNotNull(stats.getOldestEventTime());
                    assertNotNull(stats.getNewestEventTime());
                    // The Instant from stats must match the original — no timezone drift
                    assertEquals(knownTime, stats.getOldestEventTime(),
                            "oldest_event_time must match the exact Instant stored");
                    assertEquals(knownTime, stats.getNewestEventTime(),
                            "newest_event_time must match the exact Instant stored");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsTimestampPreservesNonUtcOffset(VertxTestContext testContext) throws Exception {
        // Store an event with a non-UTC valid_time (e.g. +05:30 India Standard Time)
        // The crucial point: the underlying Instant should be preserved, not silently
        // shifted by assuming the value was UTC when it wasn't.
        OffsetDateTime nonUtcTime = OffsetDateTime.of(2025, 6, 15, 20, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        Instant expectedInstant = nonUtcTime.toInstant(); // 2025-06-15T14:30:00Z

        eventStore.appendBuilder()
                .eventType("TimezoneTest")
                .payload(new StatsTestEvent("tz1", "offset-check", 1))
                .validTime(expectedInstant)
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertNotNull(stats.getOldestEventTime());
                    assertEquals(expectedInstant, stats.getOldestEventTime(),
                            "Stats timestamp must represent the same instant regardless of storage offset");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsOldestAndNewestWithMultipleEvents(VertxTestContext testContext) throws Exception {
        Instant oldest = Instant.parse("2024-01-01T00:00:00Z");
        Instant middle = Instant.parse("2024-06-15T12:00:00Z");
        Instant newest = Instant.parse("2025-01-01T00:00:00Z");

        eventStore.appendBuilder()
                .eventType("RangeTest").payload(new StatsTestEvent("r1", "oldest", 1)).validTime(oldest).execute()
                .compose(v -> eventStore.appendBuilder()
                        .eventType("RangeTest").payload(new StatsTestEvent("r2", "middle", 2)).validTime(middle).execute())
                .compose(v -> eventStore.appendBuilder()
                        .eventType("RangeTest").payload(new StatsTestEvent("r3", "newest", 3)).validTime(newest).execute())
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(3, stats.getTotalEvents());
                    assertEquals(oldest, stats.getOldestEventTime(),
                            "oldest_event_time should match the earliest valid_time");
                    assertEquals(newest, stats.getNewestEventTime(),
                            "newest_event_time should match the latest valid_time");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsSingleEventOldestEqualsNewest(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.parse("2025-03-15T10:00:00Z");

        eventStore.appendBuilder()
                .eventType("SingleTest")
                .payload(new StatsTestEvent("s1", "only-event", 1))
                .validTime(validTime)
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(1, stats.getTotalEvents());
                    assertEquals(validTime, stats.getOldestEventTime());
                    assertEquals(validTime, stats.getNewestEventTime());
                    assertEquals(stats.getOldestEventTime(), stats.getNewestEventTime(),
                            "With one event, oldest and newest should be identical");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsCountsCorrections(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        eventStore.appendBuilder()
                .eventType("CorrTest")
                .payload(new StatsTestEvent("c1", "original", 10))
                .validTime(validTime)
                .execute()
                .compose(original -> eventStore.appendCorrection(
                        original.getEventId(), "CorrTest",
                        new StatsTestEvent("c1", "corrected", 20),
                        validTime.plus(1, ChronoUnit.HOURS),
                        "Data correction"))
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(2, stats.getTotalEvents(), "original + correction = 2 total");
                    assertEquals(1, stats.getTotalCorrections(), "exactly one correction");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsEventCountsByType(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        eventStore.appendBuilder()
                .eventType("TypeA").payload(new StatsTestEvent("a1", "data", 1)).validTime(validTime).execute()
                .compose(v -> eventStore.appendBuilder()
                        .eventType("TypeA").payload(new StatsTestEvent("a2", "data", 2)).validTime(validTime).execute())
                .compose(v -> eventStore.appendBuilder()
                        .eventType("TypeB").payload(new StatsTestEvent("b1", "data", 3)).validTime(validTime).execute())
                .compose(v -> eventStore.appendBuilder()
                        .eventType("TypeC").payload(new StatsTestEvent("c1", "data", 4)).validTime(validTime).execute())
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(4, stats.getTotalEvents());
                    Map<String, Long> counts = stats.getEventCountsByType();
                    assertEquals(3, counts.size(), "should have 3 distinct event types");
                    assertEquals(2L, counts.get("TypeA"));
                    assertEquals(1L, counts.get("TypeB"));
                    assertEquals(1L, counts.get("TypeC"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsUniqueAggregateCount(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        eventStore.appendBuilder()
                .eventType("AggTest").payload(new StatsTestEvent("x1", "data", 1))
                .validTime(validTime).aggregateId("agg-1").execute()
                .compose(v -> eventStore.appendBuilder()
                        .eventType("AggTest").payload(new StatsTestEvent("x2", "data", 2))
                        .validTime(validTime).aggregateId("agg-1").execute())
                .compose(v -> eventStore.appendBuilder()
                        .eventType("AggTest").payload(new StatsTestEvent("x3", "data", 3))
                        .validTime(validTime).aggregateId("agg-2").execute())
                .compose(v -> eventStore.appendBuilder()
                        .eventType("AggTest").payload(new StatsTestEvent("x4", "data", 4))
                        .validTime(validTime).aggregateId("agg-3").execute())
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(4, stats.getTotalEvents());
                    assertEquals(3, stats.getUniqueAggregateCount(),
                            "3 distinct aggregate IDs should be counted");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsStorageSizePositiveWithData(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        eventStore.appendBuilder()
                .eventType("SizeTest")
                .payload(new StatsTestEvent("sz1", "data", 1))
                .validTime(validTime)
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertTrue(stats.getStorageSizeBytes() > 0,
                            "storage size should be positive when data exists");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsTimestampConsistentWithEventValidTime(VertxTestContext testContext) throws Exception {
        // Verify that the Instant returned by stats matches what was stored on the event itself
        Instant validTime = Instant.parse("2025-09-01T08:15:30Z");

        eventStore.appendBuilder()
                .eventType("ConsistencyTest")
                .payload(new StatsTestEvent("con1", "check", 1))
                .validTime(validTime)
                .execute()
                .compose(appended -> eventStore.getStats().map(stats -> Map.entry(appended, stats)))
                .onSuccess(pair -> testContext.verify(() -> {
                    Instant eventValidTime = pair.getKey().getValidTime();
                    Instant statsOldest = pair.getValue().getOldestEventTime();
                    Instant statsNewest = pair.getValue().getNewestEventTime();

                    assertEquals(eventValidTime, statsOldest,
                            "Stats oldest must equal the event's own validTime");
                    assertEquals(eventValidTime, statsNewest,
                            "Stats newest must equal the event's own validTime");
                    assertEquals(validTime, statsOldest,
                            "Stats oldest must equal the Instant we passed in");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsTimestampSubSecondPrecision(VertxTestContext testContext) throws Exception {
        // PostgreSQL TIMESTAMPTZ has microsecond precision; verify sub-second fidelity
        Instant preciseTime = Instant.parse("2025-06-15T14:30:00.123456Z");

        eventStore.appendBuilder()
                .eventType("PrecisionTest")
                .payload(new StatsTestEvent("p1", "microsecond", 1))
                .validTime(preciseTime)
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertNotNull(stats.getOldestEventTime());
                    // PostgreSQL TIMESTAMPTZ is microsecond-precise; truncate to micros for comparison
                    Instant expected = preciseTime.truncatedTo(ChronoUnit.MICROS);
                    assertEquals(expected, stats.getOldestEventTime(),
                            "Sub-second precision must be preserved through stats");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsCorrectionIncludedInOldestNewest(VertxTestContext testContext) throws Exception {
        // Corrections have their own valid_time which may be newer; verify it's reflected
        Instant originalTime = Instant.parse("2024-01-01T00:00:00Z");
        Instant correctionTime = Instant.parse("2025-06-01T00:00:00Z");

        eventStore.appendBuilder()
                .eventType("CorrRangeTest")
                .payload(new StatsTestEvent("cr1", "original", 1))
                .validTime(originalTime)
                .execute()
                .compose(original -> eventStore.appendCorrection(
                        original.getEventId(), "CorrRangeTest",
                        new StatsTestEvent("cr1", "corrected", 2),
                        correctionTime, "Correction extends time range"))
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(originalTime, stats.getOldestEventTime(),
                            "oldest should be the original event's valid_time");
                    assertEquals(correctionTime, stats.getNewestEventTime(),
                            "newest should be the correction's valid_time");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Negative Tests ====================

    @Test
    void testGetStatsOnClosedStoreFailsWithIllegalState(VertxTestContext testContext) throws Exception {
        eventStore.close()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.failNow("getStats() on closed store should fail"))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("closed"),
                            "Error message should mention 'closed', got: " + err.getMessage());
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetStatsEmptyCountsByTypeIsImmutable(VertxTestContext testContext) throws Exception {
        eventStore.appendBuilder()
                .eventType("ImmutableTest")
                .payload(new StatsTestEvent("i1", "data", 1))
                .validTime(Instant.now())
                .execute()
                .compose(v -> eventStore.getStats())
                .onSuccess(stats -> testContext.verify(() -> {
                    Map<String, Long> counts = stats.getEventCountsByType();
                    assertThrows(UnsupportedOperationException.class,
                            () -> counts.put("injected", 99L),
                            "eventCountsByType map should be unmodifiable");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
