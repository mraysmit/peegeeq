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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.mars.peegeeq.test.categories.TestCategories;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that surface known version-lineage bugs in PgBiTemporalEventStore.
 *
 * <p>These tests are written BEFORE any fixes — they document the bugs
 * by exercising the code paths that produce incorrect behavior:
 * <ul>
 *   <li>Bug 1: Concurrent corrections can produce duplicate version numbers
 *       (race condition in appendCorrectionWithTransaction)</li>
 *   <li>Bug 2: getAllVersions returns incomplete results when called with
 *       a correction event ID instead of the root event ID</li>
 * </ul>
 *
 * <p>Each test is expected to FAIL against the current code.
 * Once the bugs are fixed, these tests become regression guards.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class VersionLineageBugSurfacingTest {

    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_lineage_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private Vertx vertx;
    private Pool verificationPool;

    private String resolveSchema() {
        String configured = System.getProperty(DATABASE_SCHEMA_PROPERTY, "public");
        String schema = (configured == null || configured.trim().isEmpty()) ? "public" : configured.trim();
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name for test: " + schema);
        }
        return schema;
    }

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
            TestEvent that = (TestEvent) o;
            return value == that.value &&
                    Objects.equals(id, that.id) &&
                    Objects.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
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

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        // Pool for verification queries — kept open across the test
        verificationPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        Pool setupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        setupPool.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
                .recover(err -> Future.succeededFuture(null))
                .compose(v -> setupPool.close())
                .compose(v -> manager.start())
                .onSuccess(v -> {
                    factory = new BiTemporalEventStoreFactory(manager);
                    eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(
                            TestEvent.class, "bitemporal_event_log");
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
        if (eventStore != null) {
            eventStore.close();
        }

        Future<Void> closeFuture = (manager != null)
                ? manager.closeReactive()
                : Future.succeededFuture();

        closeFuture
                .compose(v -> verificationPool != null ? verificationPool.close() : Future.succeededFuture())
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

    private Future<Void> delay(long millis) {
        Promise<Void> promise = Promise.promise();
        vertx.setTimer(millis, id -> promise.complete());
        return promise.future();
    }

    // ==================== Bug 1: Concurrent version race condition ====================

    @Test
    @DisplayName("Concurrent corrections must produce unique version numbers — surfaces race in appendCorrectionWithTransaction")
    void concurrentCorrectionsMustProduceUniqueVersionNumbers(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        int concurrentCorrections = 10;

        // Step 1: Create the root event
        eventStore.appendBuilder()
                .eventType("RaceTest")
                .payload(new TestEvent("root", "original", 0))
                .validTime(validTime)
                .execute()
                .compose(rootEvent -> {
                    String rootId = rootEvent.getEventId();

                    // Step 2: Fire N corrections concurrently against the same root
                    List<Future<BiTemporalEvent<TestEvent>>> concurrentFutures = new ArrayList<>();
                    for (int i = 1; i <= concurrentCorrections; i++) {
                        concurrentFutures.add(
                                eventStore.appendCorrection(rootId, "RaceTest",
                                        new TestEvent("corr-" + i, "correction-" + i, i),
                                        validTime, "concurrent correction " + i)
                        );
                    }

                    // Step 3: Wait for all to complete (some may fail, that's acceptable)
                    return Future.join(concurrentFutures)
                            .map(cf -> rootId);
                })
                .compose(rootId -> {
                    // Step 4: Query the database directly to check version uniqueness
                    String sql = """
                            SELECT version, COUNT(*) as cnt
                            FROM bitemporal_event_log
                            WHERE event_id = $1 OR previous_version_id = $1
                            GROUP BY version
                            HAVING COUNT(*) > 1
                            """;
                    return verificationPool.preparedQuery(sql)
                            .execute(Tuple.of(rootId))
                            .map(rows -> {
                                List<String> duplicates = new ArrayList<>();
                                for (Row row : rows) {
                                    duplicates.add("version=" + row.getLong("version")
                                            + " count=" + row.getLong("cnt"));
                                }
                                return duplicates;
                            });
                })
                .onSuccess(duplicates -> testContext.verify(() -> {
                    assertTrue(duplicates.isEmpty(),
                            "Version numbers must be unique within a correction lineage, "
                                    + "but found duplicates: " + duplicates);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("Concurrent corrections must all succeed and total version count must equal N+1")
    void concurrentCorrectionsAllSucceedWithCorrectVersionCount(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        int concurrentCorrections = 10;

        eventStore.appendBuilder()
                .eventType("RaceCountTest")
                .payload(new TestEvent("root", "original", 0))
                .validTime(validTime)
                .execute()
                .compose(rootEvent -> {
                    String rootId = rootEvent.getEventId();

                    List<Future<BiTemporalEvent<TestEvent>>> concurrentFutures = new ArrayList<>();
                    for (int i = 1; i <= concurrentCorrections; i++) {
                        concurrentFutures.add(
                                eventStore.appendCorrection(rootId, "RaceCountTest",
                                        new TestEvent("corr-" + i, "data-" + i, i),
                                        validTime, "correction " + i)
                        );
                    }

                    return Future.join(concurrentFutures)
                            .compose(cf -> {
                                // Count how many succeeded
                                long successCount = 0;
                                for (int i = 0; i < concurrentCorrections; i++) {
                                    if (cf.succeeded(i)) {
                                        successCount++;
                                    }
                                }
                                final long finalSuccessCount = successCount;

                                // Verify database row count and version sequence
                                return verificationPool.preparedQuery(
                                        "SELECT version FROM bitemporal_event_log "
                                                + "WHERE event_id = $1 OR previous_version_id = $1 "
                                                + "ORDER BY version ASC")
                                        .execute(Tuple.of(rootId))
                                        .map(rows -> {
                                            List<Long> versions = new ArrayList<>();
                                            for (Row row : rows) {
                                                versions.add(row.getLong("version"));
                                            }
                                            return Map.entry(finalSuccessCount, versions);
                                        });
                            });
                })
                .onSuccess(result -> testContext.verify(() -> {
                    long successCount = result.getKey();
                    List<Long> versions = result.getValue();

                    // All corrections should succeed
                    assertEquals(concurrentCorrections, successCount,
                            "All concurrent corrections should succeed");

                    // Total rows = 1 root + N corrections
                    assertEquals(concurrentCorrections + 1, versions.size(),
                            "Should have root + " + concurrentCorrections + " corrections");

                    // Versions must be a contiguous sequence 1..N+1
                    Set<Long> uniqueVersions = new HashSet<>(versions);
                    assertEquals(versions.size(), uniqueVersions.size(),
                            "All version numbers must be unique. Got: " + versions);

                    for (long expected = 1; expected <= concurrentCorrections + 1; expected++) {
                        assertTrue(uniqueVersions.contains(expected),
                                "Expected version " + expected + " in sequence. Got: " + versions);
                    }

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Bug 2: getAllVersions with non-root event ID ====================

    @Test
    @DisplayName("getAllVersions called with correction event ID must return the full lineage family")
    void getAllVersionsWithCorrectionIdReturnsFullFamily(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        // Create root + 2 corrections (all pointing to root — star model)
        eventStore.appendBuilder()
                .eventType("LineageTest")
                .payload(new TestEvent("root", "original", 100))
                .validTime(validTime)
                .execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "LineageTest",
                                new TestEvent("c1", "correction-1", 200), validTime, "first fix")
                        .map(c1 -> Map.entry(root, c1)))
                .compose(pair -> eventStore.appendCorrection(pair.getKey().getEventId(), "LineageTest",
                                new TestEvent("c2", "correction-2", 300), validTime, "second fix")
                        .map(c2 -> List.of(pair.getKey(), pair.getValue(), c2)))
                .compose(allEvents -> {
                    BiTemporalEvent<TestEvent> root = allEvents.get(0);
                    BiTemporalEvent<TestEvent> correction1 = allEvents.get(1);
                    BiTemporalEvent<TestEvent> correction2 = allEvents.get(2);

                    // Call getAllVersions with the SECOND CORRECTION'S event ID (not the root)
                    return eventStore.getAllVersions(correction2.getEventId())
                            .map(versions -> Map.entry(allEvents, versions));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> allCreated = result.getKey();
                    List<BiTemporalEvent<TestEvent>> versionsFound = result.getValue();

                    // getAllVersions should return the FULL family (root + both corrections)
                    // regardless of which event ID in the family was passed
                    assertEquals(3, versionsFound.size(),
                            "getAllVersions must return full lineage (root + 2 corrections) "
                                    + "even when called with a correction event ID. "
                                    + "Got " + versionsFound.size() + " event(s): "
                                    + versionsFound.stream()
                                        .map(e -> e.getEventId() + "/v" + e.getVersion())
                                        .collect(Collectors.joining(", ")));

                    // Verify the full version chain
                    assertEquals(1L, versionsFound.get(0).getVersion());
                    assertEquals(2L, versionsFound.get(1).getVersion());
                    assertEquals(3L, versionsFound.get(2).getVersion());

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("getAllVersions called with first correction ID must return full lineage family")
    void getAllVersionsWithFirstCorrectionIdReturnsFullFamily(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder()
                .eventType("LineageMidTest")
                .payload(new TestEvent("root", "original", 100))
                .validTime(validTime)
                .execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "LineageMidTest",
                                new TestEvent("c1", "correction-1", 200), validTime, "first fix")
                        .map(c1 -> Map.entry(root, c1)))
                .compose(pair -> eventStore.appendCorrection(pair.getKey().getEventId(), "LineageMidTest",
                                new TestEvent("c2", "correction-2", 300), validTime, "second fix")
                        .map(c2 -> List.of(pair.getKey(), pair.getValue(), c2)))
                .compose(allEvents -> {
                    BiTemporalEvent<TestEvent> correction1 = allEvents.get(1);

                    // Call getAllVersions with the FIRST CORRECTION'S event ID
                    return eventStore.getAllVersions(correction1.getEventId())
                            .map(versions -> Map.entry(allEvents, versions));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> versionsFound = result.getValue();

                    assertEquals(3, versionsFound.size(),
                            "getAllVersions must return full lineage when called with "
                                    + "a middle correction event ID. Got " + versionsFound.size()
                                    + " event(s): "
                                    + versionsFound.stream()
                                        .map(e -> e.getEventId() + "/v" + e.getVersion())
                                        .collect(Collectors.joining(", ")));

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Bug 3: getAllVersions consistency with getAsOfTransactionTime ====================

    @Test
    @DisplayName("getAllVersions and getAsOfTransactionTime must agree on lineage when called with correction ID")
    void getAllVersionsAndAsOfTimeMustAgreeOnLineage(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder()
                .eventType("ConsistencyTest")
                .payload(new TestEvent("root", "original", 100))
                .validTime(validTime)
                .execute()
                .compose(root -> delay(100)
                        .compose(d -> eventStore.appendCorrection(root.getEventId(), "ConsistencyTest",
                                new TestEvent("c1", "correction", 200), validTime, "fix"))
                        .map(c1 -> Map.entry(root, c1)))
                .compose(pair -> {
                    BiTemporalEvent<TestEvent> correction = pair.getValue();
                    String correctionId = correction.getEventId();

                    // Call both methods with the correction's event ID
                    return Future.all(
                            eventStore.getAllVersions(correctionId),
                            eventStore.getAsOfTransactionTime(correctionId, Instant.now())
                    ).map(cf -> {
                        List<BiTemporalEvent<TestEvent>> allVersions = cf.resultAt(0);
                        BiTemporalEvent<TestEvent> asOfResult = cf.resultAt(1);
                        return Map.entry(allVersions, asOfResult);
                    });
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> allVersions = result.getKey();
                    BiTemporalEvent<TestEvent> asOfResult = result.getValue();

                    // getAsOfTransactionTime correctly walks to root and finds the latest correction
                    assertNotNull(asOfResult,
                            "getAsOfTransactionTime should find the lineage from correction ID");

                    // getAllVersions should also find the full lineage
                    assertTrue(allVersions.size() >= 2,
                            "getAllVersions must find at least root + correction when called "
                                    + "with correction ID, but found " + allVersions.size()
                                    + ". getAsOfTransactionTime found version "
                                    + asOfResult.getVersion() + " — the methods disagree.");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
