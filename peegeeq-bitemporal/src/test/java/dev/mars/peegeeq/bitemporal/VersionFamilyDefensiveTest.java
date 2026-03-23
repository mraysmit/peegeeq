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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defensive tests for version family traversal: cross-family isolation,
 * deep chains, sequential corrections from root, temporal boundary queries, metadata round-trips,
 * and error path validation.
 *
 * <p>These tests target gaps not covered by {@link VersionFamilyTopologyTest}
 * or {@link VersionLineageBugSurfacingTest}:</p>
 * <ul>
 *   <li>Cross-family isolation: two independent families must never leak into each other</li>
 *   <li>Deep chains (10 levels): recursive CTE must handle arbitrary depth</li>
 *   <li>Sequential corrections from root (6 corrections): chain model enforced by system</li>
 *   <li>Temporal boundary: getAsOfTransactionTime at exact boundaries and between versions</li>
 *   <li>Metadata: correction_reason and isCorrection round-trip fidelity</li>
 *   <li>Single-event family: getAllVersions returns exactly the one event</li>
 *   <li>Independent version numbering across families</li>
 *   <li>appendCorrection error paths</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class VersionFamilyDefensiveTest {

    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_defensive_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<DefensiveEvent> eventStore;
    private Vertx vertx;

    public static class DefensiveEvent {
        private final String id;
        private final String data;
        private final int value;

        @JsonCreator
        public DefensiveEvent(@JsonProperty("id") String id,
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
            DefensiveEvent that = (DefensiveEvent) o;
            return value == that.value && Objects.equals(id, that.id) && Objects.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
    }

    private String resolveSchema() {
        String configured = System.getProperty(DATABASE_SCHEMA_PROPERTY, "public");
        String schema = (configured == null || configured.trim().isEmpty()) ? "public" : configured.trim();
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
                    eventStore = (PgBiTemporalEventStore<DefensiveEvent>)
                            factory.createEventStore(DefensiveEvent.class, "bitemporal_event_log");
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

    private Future<Void> delay(long millis) {
        Promise<Void> promise = Promise.promise();
        vertx.setTimer(millis, id -> promise.complete());
        return promise.future();
    }

    // ==================== Cross-Family Isolation ====================

    @Test
    @DisplayName("Cross-family isolation: getAllVersions for family A must not include family B events")
    void crossFamilyIsolationGetAllVersions(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Create family A: root-A → corr-A1 → corr-A2
        eventStore.appendBuilder()
                .eventType("FamilyA").payload(new DefensiveEvent("rootA", "family-A-root", 1)).validTime(t).execute()
                .compose(rootA -> eventStore.appendCorrection(rootA.getEventId(), "FamilyA",
                        new DefensiveEvent("corrA1", "family-A-corr1", 2), t, "correct A")
                        .map(corrA1 -> Map.entry(rootA, corrA1)))
                .compose(a -> eventStore.appendCorrection(a.getValue().getEventId(), "FamilyA",
                        new DefensiveEvent("corrA2", "family-A-corr2", 3), t, "correct A again")
                        .map(corrA2 -> a.getKey()))
                // Create family B: root-B → corr-B1
                .compose(rootA -> eventStore.appendBuilder()
                        .eventType("FamilyB").payload(new DefensiveEvent("rootB", "family-B-root", 100)).validTime(t).execute()
                        .map(rootB -> Map.entry(rootA, rootB)))
                .compose(roots -> eventStore.appendCorrection(roots.getValue().getEventId(), "FamilyB",
                        new DefensiveEvent("corrB1", "family-B-corr1", 101), t, "correct B")
                        .map(corrB1 -> roots))
                // Now query family A — must get exactly 3 members, none from family B
                .compose(roots -> eventStore.getAllVersions(roots.getKey().getEventId())
                        .map(familyA -> Map.entry(roots, familyA)))
                // Also query family B — must get exactly 2 members, none from family A
                .compose(pair -> eventStore.getAllVersions(pair.getKey().getValue().getEventId())
                        .map(familyB -> Map.entry(pair.getValue(), familyB)))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> familyA = result.getKey();
                    List<BiTemporalEvent<DefensiveEvent>> familyB = result.getValue();

                    assertEquals(3, familyA.size(),
                            "Family A must have exactly 3 members (root + 2 corrections)");
                    assertEquals(2, familyB.size(),
                            "Family B must have exactly 2 members (root + 1 correction)");

                    // Verify no event IDs overlap
                    Set<String> familyAIds = familyA.stream()
                            .map(BiTemporalEvent::getEventId).collect(Collectors.toSet());
                    Set<String> familyBIds = familyB.stream()
                            .map(BiTemporalEvent::getEventId).collect(Collectors.toSet());
                    familyAIds.retainAll(familyBIds);
                    assertTrue(familyAIds.isEmpty(),
                            "Families must not share any event IDs — cross-family contamination detected");

                    // Verify family A contains only FamilyA event types
                    assertTrue(familyA.stream().allMatch(e -> "FamilyA".equals(e.getEventType())),
                            "Family A must contain only FamilyA events");
                    assertTrue(familyB.stream().allMatch(e -> "FamilyB".equals(e.getEventType())),
                            "Family B must contain only FamilyB events");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Cross-family isolation: getAsOfTransactionTime for family A must not return family B event")
    void crossFamilyIsolationGetAsOfTransactionTime(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Family A: root-A → corr-A1
        eventStore.appendBuilder()
                .eventType("IsoA").payload(new DefensiveEvent("isoA", "family-A", 1)).validTime(t).execute()
                .compose(rootA -> eventStore.appendCorrection(rootA.getEventId(), "IsoA",
                        new DefensiveEvent("isoA1", "family-A-corr", 2), t, "correct A")
                        .map(corrA -> rootA))
                // Family B: root-B (higher version payload to distinguish)
                .compose(rootA -> eventStore.appendBuilder()
                        .eventType("IsoB").payload(new DefensiveEvent("isoB", "family-B", 999)).validTime(t).execute()
                        .map(rootB -> Map.entry(rootA, rootB)))
                // Query family A as-of now
                .compose(roots -> eventStore.getAsOfTransactionTime(roots.getKey().getEventId(), Instant.now())
                        .map(resultA -> Map.entry(roots, resultA)))
                .onSuccess(pair -> testContext.verify(() -> {
                    BiTemporalEvent<DefensiveEvent> resultA = pair.getValue();
                    assertNotNull(resultA, "getAsOfTransactionTime must return a result for family A");
                    assertEquals("IsoA", resultA.getEventType(),
                            "Result must be from family A, not family B");
                    assertNotEquals(999, resultA.getPayload().getValue(),
                            "Result payload must not be from family B");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Deep Chain (10 Levels) ====================

    @Test
    @DisplayName("Deep chain: 10-level chain getAllVersions from leaf returns all 10")
    void deepChain10LevelsGetAllVersionsFromLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        int depth = 10;

        // Build chain of depth 10: each correction points to the previous
        eventStore.appendBuilder()
                .eventType("DeepChain10").payload(new DefensiveEvent("v1", "root", 1)).validTime(t).execute()
                .compose(root -> buildChain(root, 2, depth, t))
                .compose(allEvents -> {
                    String leafId = allEvents.get(allEvents.size() - 1).getEventId();
                    return eventStore.getAllVersions(leafId).map(versions -> Map.entry(allEvents, versions));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> versions = result.getValue();
                    assertEquals(depth, versions.size(),
                            "10-level deep chain: getAllVersions from leaf must return all " + depth + " versions. "
                                    + "Got " + versions.size());

                    // Verify version sequence is 1..10
                    for (int i = 0; i < versions.size(); i++) {
                        assertEquals(i + 1L, versions.get(i).getVersion(),
                                "Version at index " + i + " must be " + (i + 1));
                    }

                    // Verify root is not a correction, all others are
                    assertFalse(versions.get(0).isCorrection(), "Root must not be a correction");
                    for (int i = 1; i < versions.size(); i++) {
                        assertTrue(versions.get(i).isCorrection(),
                                "Version " + (i + 1) + " must be a correction");
                    }

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Deep chain: 10-level chain getAllVersions from middle returns all 10")
    void deepChain10LevelsGetAllVersionsFromMiddle(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        int depth = 10;

        eventStore.appendBuilder()
                .eventType("DeepMid10").payload(new DefensiveEvent("v1", "root", 1)).validTime(t).execute()
                .compose(root -> buildChain(root, 2, depth, t))
                .compose(allEvents -> {
                    // Query from the 5th event (middle of 10)
                    String middleId = allEvents.get(4).getEventId();
                    return eventStore.getAllVersions(middleId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(depth, versions.size(),
                            "getAllVersions from middle of 10-level chain must return all " + depth);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * Recursively builds a correction chain from currentVersion to targetDepth.
     */
    private Future<List<BiTemporalEvent<DefensiveEvent>>> buildChain(
            BiTemporalEvent<DefensiveEvent> previous, int currentVersion, int targetDepth, Instant t) {
        if (currentVersion > targetDepth) {
            return Future.succeededFuture(List.of(previous));
        }
        return eventStore.appendCorrection(previous.getEventId(), previous.getEventType(),
                        new DefensiveEvent("v" + currentVersion, "level-" + currentVersion, currentVersion),
                        t, "correction level " + currentVersion)
                .compose(correction -> buildChain(correction, currentVersion + 1, targetDepth, t)
                        .map(rest -> {
                            List<BiTemporalEvent<DefensiveEvent>> all = new ArrayList<>();
                            all.add(previous);
                            all.addAll(rest);
                            return all;
                        }));
    }

    // ==================== Sequential Corrections (6 from root) ====================

    @Test
    @DisplayName("Sequential corrections: root with 6 corrections, getAllVersions returns all 7")
    void sequentialSixCorrectionsGetAllVersions(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        int childCount = 6;

        eventStore.appendBuilder()
                .eventType("WideFanOut").payload(new DefensiveEvent("root", "root", 0)).validTime(t).execute()
                .compose(root -> buildFanOut(root, childCount, t)
                        .map(children -> Map.entry(root, children)))
                .compose(pair -> {
                    // Query from the last child
                    BiTemporalEvent<DefensiveEvent> lastChild = pair.getValue().get(pair.getValue().size() - 1);
                    return eventStore.getAllVersions(lastChild.getEventId())
                            .map(versions -> Map.entry(pair, versions));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> versions = result.getValue();
                    int expectedTotal = 1 + childCount; // root + children
                    assertEquals(expectedTotal, versions.size(),
                            "Sequential corrections: getAllVersions must return root + " + childCount + " corrections = "
                                    + expectedTotal + ". Got " + versions.size());

                    // All version numbers must be unique
                    Set<Long> uniqueVersions = versions.stream()
                            .map(BiTemporalEvent::getVersion).collect(Collectors.toSet());
                    assertEquals(versions.size(), uniqueVersions.size(),
                            "All version numbers must be unique in correction chain");

                    // Version 1 must be the root
                    assertEquals(1L, versions.get(0).getVersion());
                    assertFalse(versions.get(0).isCorrection());

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Sequential corrections: getAsOfTransactionTime from any member returns latest version")
    void sequentialCorrectionsGetAsOfTransactionTime(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        int childCount = 4;

        eventStore.appendBuilder()
                .eventType("WideFanAsOf").payload(new DefensiveEvent("root", "root", 0)).validTime(t).execute()
                .compose(root -> buildFanOut(root, childCount, t)
                        .map(children -> Map.entry(root, children)))
                .compose(pair -> {
                    // Query from the first child
                    BiTemporalEvent<DefensiveEvent> firstChild = pair.getValue().get(0);
                    return eventStore.getAsOfTransactionTime(firstChild.getEventId(), Instant.now());
                })
                .onSuccess(result -> testContext.verify(() -> {
                    assertNotNull(result, "getAsOfTransactionTime must return a result from correction chain");
                    assertTrue(result.getVersion() >= 1L,
                            "Result must have a valid version number");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * Builds N sequential corrections from the same root.
     * With chain model enforcement, the system links each correction to the latest version,
     * producing a linear chain regardless of the caller always passing root's event ID.
     */
    private Future<List<BiTemporalEvent<DefensiveEvent>>> buildFanOut(
            BiTemporalEvent<DefensiveEvent> root, int count, Instant t) {
        Future<List<BiTemporalEvent<DefensiveEvent>>> result = Future.succeededFuture(new ArrayList<>());
        for (int i = 1; i <= count; i++) {
            final int idx = i;
            result = result.compose(children ->
                    eventStore.appendCorrection(root.getEventId(), root.getEventType(),
                                    new DefensiveEvent("child-" + idx, "fan-out-child-" + idx, idx),
                                    t, "fan-out correction " + idx)
                            .map(child -> {
                                children.add(child);
                                return children;
                            }));
        }
        return result;
    }

    // ==================== Temporal Boundary Queries ====================

    @Test
    @DisplayName("getAsOfTransactionTime before any family events returns null")
    void getAsOfTransactionTimeBeforeAnyEventReturnsNull(VertxTestContext testContext) throws Exception {
        Instant farPast = Instant.parse("2000-01-01T00:00:00Z");
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("BeforeAll").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "BeforeAll",
                        new DefensiveEvent("c", "corr", 2), t, "correct")
                        .map(corr -> root))
                .compose(root -> eventStore.getAsOfTransactionTime(root.getEventId(), farPast))
                .onSuccess(result -> testContext.verify(() -> {
                    assertNull(result,
                            "getAsOfTransactionTime queried before any events must return null");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime between v1 and v2 returns v1 only")
    void getAsOfTransactionTimeBetweenVersionsReturnsEarlier(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("BetweenVer").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> {
                    // Capture time after v1 is committed but before v2 is created
                    return delay(100).compose(d -> {
                        Instant betweenTime = Instant.now();
                        return delay(100)
                                .compose(d2 -> eventStore.appendCorrection(root.getEventId(), "BetweenVer",
                                        new DefensiveEvent("c", "corr", 2), t, "correct"))
                                .map(corr -> Map.entry(root, betweenTime));
                    });
                })
                .compose(pair -> eventStore.getAsOfTransactionTime(pair.getKey().getEventId(), pair.getValue()))
                .onSuccess(result -> testContext.verify(() -> {
                    assertNotNull(result, "Must return v1 at time between v1 and v2 creation");
                    assertEquals(1L, result.getVersion(),
                            "At a time between v1 and v2 creation, only v1 should be visible");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime at exact transaction_time of v1 returns v1 (<= boundary)")
    void getAsOfTransactionTimeAtExactTimeBoundary(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ExactBound").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> {
                    // The transaction_time is set by the database; query at exactly that time
                    Instant txTime = root.getTransactionTime();
                    return eventStore.getAsOfTransactionTime(root.getEventId(), txTime);
                })
                .onSuccess(result -> testContext.verify(() -> {
                    assertNotNull(result,
                            "getAsOfTransactionTime at exact transaction_time must return the event (<= semantics)");
                    assertEquals(1L, result.getVersion());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime with tree topology returns latest visible version")
    void getAsOfTransactionTimeWithTreeTopology(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // A(v1) → B(v2, prev=A), A → C(v3, prev=A), B → D(v4, prev=B)
        eventStore.appendBuilder()
                .eventType("TreeAsOf").payload(new DefensiveEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> delay(50).compose(d ->
                        eventStore.appendCorrection(a.getEventId(), "TreeAsOf",
                                new DefensiveEvent("B", "child-1", 2), t, "first correction of A")
                                .map(b -> Map.entry(a, b))))
                .compose(ab -> delay(50).compose(d ->
                        eventStore.appendCorrection(ab.getKey().getEventId(), "TreeAsOf",
                                new DefensiveEvent("C", "child-2", 3), t, "second correction of A")
                                .map(c -> List.of(ab.getKey(), ab.getValue(), c))))
                .compose(abc -> delay(50).compose(d ->
                        eventStore.appendCorrection(abc.get(1).getEventId(), "TreeAsOf",
                                new DefensiveEvent("D", "sub-child", 4), t, "correction of B")
                                .map(dd -> {
                                    List<BiTemporalEvent<DefensiveEvent>> all = new ArrayList<>(abc);
                                    all.add(dd);
                                    return all;
                                })))
                .compose(all -> {
                    // Query from C (sibling branch) at current time — should see v4 (D) as latest
                    String cId = all.get(2).getEventId();
                    return eventStore.getAsOfTransactionTime(cId, Instant.now())
                            .map(result -> Map.entry(all, result));
                })
                .onSuccess(pair -> testContext.verify(() -> {
                    BiTemporalEvent<DefensiveEvent> result = pair.getValue();
                    assertNotNull(result, "getAsOfTransactionTime from tree sibling must return a result");
                    assertEquals(4L, result.getVersion(),
                            "Latest version in tree family is v4 (D). "
                                    + "getAsOfTransactionTime from sibling C must resolve to the latest version.");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Metadata Round-Trip: correction_reason, isCorrection ====================

    @Test
    @DisplayName("correction_reason is preserved through getAllVersions round-trip")
    void correctionReasonPreservedInGetAllVersions(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        String reason1 = "First correction: price adjustment";
        String reason2 = "Second correction: regulatory update";

        eventStore.appendBuilder()
                .eventType("ReasonTest").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "ReasonTest",
                        new DefensiveEvent("c1", "corr1", 2), t, reason1)
                        .map(c1 -> root))
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "ReasonTest",
                        new DefensiveEvent("c2", "corr2", 3), t, reason2)
                        .map(c2 -> root))
                .compose(root -> eventStore.getAllVersions(root.getEventId()))
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size());

                    // Root has no correction reason
                    assertNull(versions.get(0).getCorrectionReason(),
                            "Root event must have null correction reason");
                    assertFalse(versions.get(0).isCorrection(),
                            "Root event must not be marked as correction");

                    // First correction
                    assertEquals(reason1, versions.get(1).getCorrectionReason(),
                            "First correction reason must be preserved exactly");
                    assertTrue(versions.get(1).isCorrection(),
                            "First correction must be marked as correction");

                    // Second correction
                    assertEquals(reason2, versions.get(2).getCorrectionReason(),
                            "Second correction reason must be preserved exactly");
                    assertTrue(versions.get(2).isCorrection(),
                            "Second correction must be marked as correction");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Single-Event Family ====================

    @Test
    @DisplayName("Single event (no corrections): getAllVersions returns exactly one event")
    void singleEventFamilyGetAllVersions(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("SingleEvent").payload(new DefensiveEvent("solo", "only-event", 42)).validTime(t).execute()
                .compose(solo -> eventStore.getAllVersions(solo.getEventId())
                        .map(versions -> Map.entry(solo, versions)))
                .onSuccess(pair -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> versions = pair.getValue();
                    assertEquals(1, versions.size(),
                            "Single event with no corrections must return exactly 1 version");
                    assertEquals(1L, versions.get(0).getVersion());
                    assertFalse(versions.get(0).isCorrection());
                    assertNull(versions.get(0).getPreviousVersionId());
                    assertEquals(pair.getKey().getEventId(), versions.get(0).getEventId());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Single event: getAsOfTransactionTime at current time returns that event")
    void singleEventFamilyGetAsOfTransactionTime(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("SingleAsOf").payload(new DefensiveEvent("solo", "only-event", 42)).validTime(t).execute()
                .compose(solo -> eventStore.getAsOfTransactionTime(solo.getEventId(), Instant.now())
                        .map(result -> Map.entry(solo, result)))
                .onSuccess(pair -> testContext.verify(() -> {
                    BiTemporalEvent<DefensiveEvent> result = pair.getValue();
                    assertNotNull(result, "Single event must be returned by getAsOfTransactionTime");
                    assertEquals(pair.getKey().getEventId(), result.getEventId());
                    assertEquals(1L, result.getVersion());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Independent Version Numbering Across Families ====================

    @Test
    @DisplayName("Two independent families have independent version numbering starting at 1")
    void independentFamiliesHaveIndependentVersionNumbers(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Family 1: root → 2 corrections (versions 1, 2, 3)
        eventStore.appendBuilder()
                .eventType("IndepA").payload(new DefensiveEvent("a1", "family-A", 1)).validTime(t).execute()
                .compose(rootA -> eventStore.appendCorrection(rootA.getEventId(), "IndepA",
                        new DefensiveEvent("a2", "family-A-v2", 2), t, "corr 1")
                        .compose(c1 -> eventStore.appendCorrection(c1.getEventId(), "IndepA",
                                new DefensiveEvent("a3", "family-A-v3", 3), t, "corr 2"))
                        .map(c2 -> rootA))
                // Family 2: root → 1 correction (versions 1, 2)
                .compose(rootA -> eventStore.appendBuilder()
                        .eventType("IndepB").payload(new DefensiveEvent("b1", "family-B", 10)).validTime(t).execute()
                        .compose(rootB -> eventStore.appendCorrection(rootB.getEventId(), "IndepB",
                                new DefensiveEvent("b2", "family-B-v2", 11), t, "corr B")
                                .map(c -> rootB))
                        .map(rootB -> Map.entry(rootA, rootB)))
                // Query both families
                .compose(roots -> Future.all(
                        eventStore.getAllVersions(roots.getKey().getEventId()),
                        eventStore.getAllVersions(roots.getValue().getEventId())
                ).map(cf -> {
                    List<BiTemporalEvent<DefensiveEvent>> famA = cf.resultAt(0);
                    List<BiTemporalEvent<DefensiveEvent>> famB = cf.resultAt(1);
                    return Map.entry(famA, famB);
                }))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> famA = result.getKey();
                    List<BiTemporalEvent<DefensiveEvent>> famB = result.getValue();

                    assertEquals(3, famA.size(), "Family A: 3 versions");
                    assertEquals(2, famB.size(), "Family B: 2 versions");

                    // Both families start at version 1
                    assertEquals(1L, famA.get(0).getVersion(), "Family A root must be version 1");
                    assertEquals(1L, famB.get(0).getVersion(), "Family B root must be version 1");

                    // Family A versions: 1, 2, 3
                    assertEquals(List.of(1L, 2L, 3L),
                            famA.stream().map(BiTemporalEvent::getVersion).collect(Collectors.toList()));
                    // Family B versions: 1, 2
                    assertEquals(List.of(1L, 2L),
                            famB.stream().map(BiTemporalEvent::getVersion).collect(Collectors.toList()));

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Same validTime for All Versions ====================

    @Test
    @DisplayName("All corrections with identical validTime: version ordering is deterministic")
    void sameValidTimeForAllVersionsOrderingIsDeterministic(VertxTestContext testContext) throws Exception {
        Instant sameTime = Instant.parse("2025-06-15T12:00:00Z");

        eventStore.appendBuilder()
                .eventType("SameTime").payload(new DefensiveEvent("r", "root", 1)).validTime(sameTime).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "SameTime",
                        new DefensiveEvent("c1", "corr1", 2), sameTime, "correction 1")
                        .compose(c1 -> eventStore.appendCorrection(c1.getEventId(), "SameTime",
                                new DefensiveEvent("c2", "corr2", 3), sameTime, "correction 2"))
                        .map(c2 -> root))
                .compose(root -> eventStore.getAllVersions(root.getEventId()))
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size());

                    // Even though all validTimes are identical, versions must be strictly increasing
                    assertEquals(1L, versions.get(0).getVersion());
                    assertEquals(2L, versions.get(1).getVersion());
                    assertEquals(3L, versions.get(2).getVersion());

                    // All validTimes identical
                    for (BiTemporalEvent<DefensiveEvent> v : versions) {
                        assertEquals(sameTime, v.getValidTime(),
                                "All versions share the same validTime");
                    }

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== appendCorrection Error Paths ====================

    @Test
    @DisplayName("appendCorrection to non-existent event ID fails with IllegalArgumentException")
    void appendCorrectionNonExistentEventFails(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        String fakeId = UUID.randomUUID().toString();

        eventStore.appendCorrection(fakeId, "NoSuchEvent",
                        new DefensiveEvent("x", "impossible", 0), t, "correcting nothing")
                .onSuccess(result -> testContext.failNow(
                        "appendCorrection to non-existent event must fail, but succeeded with: " + result.getEventId()))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err,
                            "Must fail with IllegalArgumentException, got: " + err.getClass().getName());
                    assertTrue(err.getMessage().contains("non-existent"),
                            "Error message must mention 'non-existent', got: " + err.getMessage());
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("appendCorrection with null correctionReason throws NullPointerException")
    void appendCorrectionNullReasonThrowsNpe(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("NullReasonTest").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> {
                    assertThrows(NullPointerException.class,
                            () -> eventStore.appendCorrection(root.getEventId(), "NullReasonTest",
                                    new DefensiveEvent("c", "corr", 2), t, null));
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("appendCorrection with null originalEventId throws NullPointerException")
    void appendCorrectionNullOriginalIdThrowsNpe(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        assertThrows(NullPointerException.class,
                () -> eventStore.appendCorrection(null, "TestType",
                        new DefensiveEvent("c", "corr", 2), t, "some reason"));
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime with null asOfTime throws NullPointerException")
    void getAsOfTransactionTimeWithNullTimeFails(VertxTestContext testContext) throws Exception {
        assertThrows(NullPointerException.class,
                () -> eventStore.getAsOfTransactionTime("any-id", null));
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Payload Fidelity Through getAllVersions ====================

    @Test
    @DisplayName("getAllVersions preserves payload data for each version in family")
    void getAllVersionsPreservesPayloadData(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("PayloadTest").payload(new DefensiveEvent("original", "Initial submission", 100)).validTime(t).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "PayloadTest",
                        new DefensiveEvent("corrected", "Price adjusted to 105", 105), t, "price correction")
                        .map(corr -> root))
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "PayloadTest",
                        new DefensiveEvent("corrected-again", "Regulatory update to 110", 110), t, "regulatory")
                        .map(c2 -> root))
                .compose(root -> eventStore.getAllVersions(root.getEventId()))
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size());

                    // Verify each version's payload is distinct and preserved
                    assertEquals("original", versions.get(0).getPayload().getId());
                    assertEquals("Initial submission", versions.get(0).getPayload().getData());
                    assertEquals(100, versions.get(0).getPayload().getValue());

                    assertEquals("corrected", versions.get(1).getPayload().getId());
                    assertEquals("Price adjusted to 105", versions.get(1).getPayload().getData());
                    assertEquals(105, versions.get(1).getPayload().getValue());

                    assertEquals("corrected-again", versions.get(2).getPayload().getId());
                    assertEquals("Regulatory update to 110", versions.get(2).getPayload().getData());
                    assertEquals(110, versions.get(2).getPayload().getValue());

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Transaction Time Monotonicity ====================

    @Test
    @DisplayName("Transaction times are monotonically non-decreasing within getAllVersions results")
    void transactionTimesAreMonotonicallyNonDecreasing(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("TxMonotone").payload(new DefensiveEvent("r", "root", 1)).validTime(t).execute()
                .compose(root -> delay(50)
                        .compose(d -> eventStore.appendCorrection(root.getEventId(), "TxMonotone",
                                new DefensiveEvent("c1", "corr1", 2), t, "correction 1"))
                        .map(c1 -> root))
                .compose(root -> delay(50)
                        .compose(d -> eventStore.appendCorrection(root.getEventId(), "TxMonotone",
                                new DefensiveEvent("c2", "corr2", 3), t, "correction 2"))
                        .map(c2 -> root))
                .compose(root -> eventStore.getAllVersions(root.getEventId()))
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size());

                    for (int i = 1; i < versions.size(); i++) {
                        Instant prev = versions.get(i - 1).getTransactionTime();
                        Instant curr = versions.get(i).getTransactionTime();
                        assertTrue(!curr.isBefore(prev),
                                "Transaction times must be monotonically non-decreasing. "
                                        + "Version " + versions.get(i - 1).getVersion() + " txTime=" + prev
                                        + " vs version " + versions.get(i).getVersion() + " txTime=" + curr);
                    }

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Chain from root: getAllVersions from middle member ====================

    @Test
    @DisplayName("Chain model: getAllVersions from middle correction returns full family")
    void chainModelGetAllVersionsFromMiddle(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Chain model: system enforces A→B→C→D regardless of which event ID caller passes
        eventStore.appendBuilder()
                .eventType("StarMid").payload(new DefensiveEvent("A", "root", 1)).validTime(t).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "StarMid",
                        new DefensiveEvent("B", "corr-1", 2), t, "first")
                        .map(b -> Map.entry(root, b)))
                .compose(pair -> eventStore.appendCorrection(pair.getKey().getEventId(), "StarMid",
                        new DefensiveEvent("C", "corr-2", 3), t, "second")
                        .map(c -> List.of(pair.getKey(), pair.getValue(), c)))
                .compose(abc -> eventStore.appendCorrection(abc.get(0).getEventId(), "StarMid",
                        new DefensiveEvent("D", "corr-3", 4), t, "third")
                        .map(d -> {
                            List<BiTemporalEvent<DefensiveEvent>> all = new ArrayList<>(abc);
                            all.add(d);
                            return all;
                        }))
                .compose(all -> {
                    // Query from B (middle member of star)
                    String bId = all.get(1).getEventId();
                    return eventStore.getAllVersions(bId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(4, versions.size(),
                            "Chain model: getAllVersions from middle member B must return all 4 family members (A, B, C, D)");
                    assertEquals(1L, versions.get(0).getVersion());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Sequential chain from root: getAllVersions from deep leaf ====================

    @Test
    @DisplayName("Sequential chain: getAllVersions from deep leaf returns all family members")
    void sequentialChainGetAllVersionsFromDeepLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Chain model: system resolves latest version for each correction.
        // Regardless of which event ID caller passes, the chain is: A→B→C→D→E
        eventStore.appendBuilder()
                .eventType("MixedWide").payload(new DefensiveEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "MixedWide",
                        new DefensiveEvent("B", "branch-1", 2), t, "branch 1")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getKey().getEventId(), "MixedWide",
                        new DefensiveEvent("C", "branch-2", 3), t, "branch 2")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(abc -> eventStore.appendCorrection(abc.get(0).getEventId(), "MixedWide",
                        new DefensiveEvent("D", "branch-3", 4), t, "branch 3")
                        .map(d -> {
                            List<BiTemporalEvent<DefensiveEvent>> all = new ArrayList<>(abc);
                            all.add(d);
                            return all;
                        }))
                .compose(abcd -> eventStore.appendCorrection(abcd.get(1).getEventId(), "MixedWide",
                        new DefensiveEvent("E", "sub-chain", 5), t, "sub-chain from B")
                        .map(e -> {
                            List<BiTemporalEvent<DefensiveEvent>> all = new ArrayList<>(abcd);
                            all.add(e);
                            return all;
                        }))
                .compose(all -> {
                    // Query from E (deepest leaf: child of B, which is child of A)
                    String eId = all.get(4).getEventId();
                    return eventStore.getAllVersions(eId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(5, versions.size(),
                            "Sequential chain: getAllVersions from deep leaf E must return all 5 members");

                    // Version numbers must be unique and sequential
                    Set<Long> uniqueVersions = versions.stream()
                            .map(BiTemporalEvent::getVersion).collect(Collectors.toSet());
                    assertEquals(5, uniqueVersions.size(), "All 5 version numbers must be unique");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== getAllVersions and getAsOfTransactionTime Consistency ====================

    @Test
    @DisplayName("Consistency: getAllVersions last element matches getAsOfTransactionTime")
    void consistencyBetweenMethodsAcrossTopologies(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Build a chain: A → B → C (chain model enforced)
        eventStore.appendBuilder()
                .eventType("ConsistCheck").payload(new DefensiveEvent("A", "root", 1)).validTime(t).execute()
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "ConsistCheck",
                        new DefensiveEvent("B", "corr-1", 2), t, "first")
                        .map(b -> root))
                .compose(root -> eventStore.appendCorrection(root.getEventId(), "ConsistCheck",
                        new DefensiveEvent("C", "corr-2", 3), t, "second")
                        .map(c -> root))
                .compose(root -> Future.all(
                        eventStore.getAllVersions(root.getEventId()),
                        eventStore.getAsOfTransactionTime(root.getEventId(), Instant.now())
                ).map(cf -> {
                    List<BiTemporalEvent<DefensiveEvent>> allVersions = cf.resultAt(0);
                    BiTemporalEvent<DefensiveEvent> asOfResult = cf.resultAt(1);
                    return Map.entry(allVersions, asOfResult);
                }))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<DefensiveEvent>> allVersions = result.getKey();
                    BiTemporalEvent<DefensiveEvent> asOfResult = result.getValue();

                    assertNotNull(asOfResult);
                    assertFalse(allVersions.isEmpty());

                    // Latest version from getAllVersions must match getAsOfTransactionTime
                    BiTemporalEvent<DefensiveEvent> latestFromAll = allVersions.get(allVersions.size() - 1);
                    assertEquals(latestFromAll.getVersion(), asOfResult.getVersion(),
                            "getAllVersions last element and getAsOfTransactionTime must agree on latest version");
                    assertEquals(latestFromAll.getEventId(), asOfResult.getEventId(),
                            "Both methods must return the same event ID for the latest version");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Temporal Progression Through a Chain ====================

    @Test
    @DisplayName("Temporal progression: getAsOfTransactionTime at 3 different points yields correct version")
    void temporalProgressionThreeTimeSlices(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Create root, record time, create v2, record time, create v3
        eventStore.appendBuilder()
                .eventType("TemporalProg").payload(new DefensiveEvent("v1", "root", 1)).validTime(t).execute()
                .compose(root -> delay(100).map(d -> root))
                .compose(root -> {
                    Instant afterV1 = Instant.now();
                    return delay(100)
                            .compose(d -> eventStore.appendCorrection(root.getEventId(), "TemporalProg",
                                    new DefensiveEvent("v2", "corr1", 2), t, "correction 1"))
                            .compose(c1 -> delay(100).map(d -> c1))
                            .compose(c1 -> {
                                Instant afterV2 = Instant.now();
                                return delay(100)
                                        .compose(d -> eventStore.appendCorrection(c1.getEventId(), "TemporalProg",
                                                new DefensiveEvent("v3", "corr2", 3), t, "correction 2"))
                                        .map(c2 -> {
                                            // Return root ID + the two recorded times
                                            Map<String, Object> ctx = new HashMap<>();
                                            ctx.put("rootId", root.getEventId());
                                            ctx.put("afterV1", afterV1);
                                            ctx.put("afterV2", afterV2);
                                            return ctx;
                                        });
                            });
                })
                .compose(ctx -> {
                    String rootId = (String) ctx.get("rootId");
                    Instant afterV1 = (Instant) ctx.get("afterV1");
                    Instant afterV2 = (Instant) ctx.get("afterV2");

                    // Query at 3 points: afterV1, afterV2, now
                    return Future.all(
                            eventStore.getAsOfTransactionTime(rootId, afterV1),
                            eventStore.getAsOfTransactionTime(rootId, afterV2),
                            eventStore.getAsOfTransactionTime(rootId, Instant.now())
                    ).map(cf -> {
                        BiTemporalEvent<DefensiveEvent> atV1 = cf.resultAt(0);
                        BiTemporalEvent<DefensiveEvent> atV2 = cf.resultAt(1);
                        BiTemporalEvent<DefensiveEvent> atNow = cf.resultAt(2);
                        return List.of(atV1, atV2, atNow);
                    });
                })
                .onSuccess(results -> testContext.verify(() -> {
                    BiTemporalEvent<DefensiveEvent> atV1 = results.get(0);
                    BiTemporalEvent<DefensiveEvent> atV2 = results.get(1);
                    BiTemporalEvent<DefensiveEvent> atNow = results.get(2);

                    // After v1 was created, only v1 should be visible
                    assertNotNull(atV1, "After v1 creation, v1 must be visible");
                    assertEquals(1L, atV1.getVersion(), "After v1 creation, version must be 1");

                    // After v2 was created, v2 should be the latest visible
                    assertNotNull(atV2, "After v2 creation, result must exist");
                    assertEquals(2L, atV2.getVersion(), "After v2 creation, latest version must be 2");

                    // At current time, v3 (latest) should be visible
                    assertNotNull(atNow, "At current time, result must exist");
                    assertEquals(3L, atNow.getVersion(), "At current time, latest version must be 3");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== appendCorrection on Closed Store ====================

    @Test
    @DisplayName("appendCorrection on closed store returns failed future with IllegalStateException")
    void appendCorrectionOnClosedStoreFails(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();
        String fakeId = UUID.randomUUID().toString();

        eventStore.close()
                .compose(v -> eventStore.appendCorrection(fakeId, "ClosedTest",
                        new DefensiveEvent("x", "impossible", 0), t, "correcting on closed store"))
                .onSuccess(result -> testContext.failNow("appendCorrection on closed store must fail"))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("closed"));
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }
}
