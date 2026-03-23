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
 * Tests that verify {@code getAllVersions()} and {@code getAsOfTransactionTime()}
 * work correctly with the enforced chain correction model.
 *
 * <p><b>Chain model:</b> each correction's {@code previous_version_id} always
 * points to the current latest version in the family (A→B→C→D). The system
 * enforces this regardless of which event ID the caller passes to
 * {@code appendCorrection}. Star and tree topologies are no longer possible
 * in production.</p>
 *
 * <p>The recursive CTE traversal must handle any entry point in the lineage.
 * These tests verify that contract.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class VersionFamilyTopologyTest {

    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_topology_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TopologyEvent> eventStore;
    private Vertx vertx;

    public static class TopologyEvent {
        private final String id;
        private final String data;
        private final int value;

        @JsonCreator
        public TopologyEvent(@JsonProperty("id") String id,
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
            TopologyEvent that = (TopologyEvent) o;
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
                    eventStore = (PgBiTemporalEventStore<TopologyEvent>)
                            factory.createEventStore(TopologyEvent.class, "bitemporal_event_log");
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

    // ==================== Chain Topology: A → B → C ====================

    @Test
    @DisplayName("Chain: getAllVersions from root returns full chain A→B→C")
    void chainTopologyGetAllVersionsFromRoot(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // A → B → C (each correction points to the previous, not root)
        eventStore.appendBuilder()
                .eventType("ChainTest").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "ChainTest",
                        new TopologyEvent("B", "corr-of-root", 2), t, "correct A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainTest",
                        new TopologyEvent("C", "corr-of-corr", 3), t, "correct B")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> eventStore.getAllVersions(all.get(0).getEventId())
                        .map(versions -> Map.entry(all, versions)))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TopologyEvent>> versions = result.getValue();
                    assertEquals(3, versions.size(),
                            "getAllVersions from root must return full chain (A, B, C)");
                    assertEquals(1L, versions.get(0).getVersion());
                    assertEquals(2L, versions.get(1).getVersion());
                    assertEquals(3L, versions.get(2).getVersion());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Chain: getAllVersions from middle node B returns full chain A→B→C")
    void chainTopologyGetAllVersionsFromMiddle(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ChainMid").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "ChainMid",
                        new TopologyEvent("B", "corr-of-root", 2), t, "correct A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainMid",
                        new TopologyEvent("C", "corr-of-corr", 3), t, "correct B")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> {
                    // Call with B's event ID (middle of chain)
                    String middleId = all.get(1).getEventId();
                    return eventStore.getAllVersions(middleId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size(),
                            "getAllVersions from middle node must return full chain. "
                                    + "Got: " + versions.stream()
                                    .map(e -> "v" + e.getVersion())
                                    .collect(Collectors.joining(", ")));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Chain: getAllVersions from leaf node C returns full chain A→B→C")
    void chainTopologyGetAllVersionsFromLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ChainLeaf").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "ChainLeaf",
                        new TopologyEvent("B", "corr-of-root", 2), t, "correct A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainLeaf",
                        new TopologyEvent("C", "corr-of-corr", 3), t, "correct B")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> {
                    // Call with C's event ID (leaf of chain)
                    String leafId = all.get(2).getEventId();
                    return eventStore.getAllVersions(leafId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(3, versions.size(),
                            "getAllVersions from leaf must return full chain. "
                                    + "Got: " + versions.stream()
                                    .map(e -> "v" + e.getVersion())
                                    .collect(Collectors.joining(", ")));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Deep Chain: A → B → C → D ====================

    @Test
    @DisplayName("Deep chain: 4-level chain A→B→C→D getAllVersions from any node returns all 4")
    void deepChainGetAllVersionsFromLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("DeepChain").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "DeepChain",
                        new TopologyEvent("B", "v2", 2), t, "level 2")
                        .map(b -> List.of(a, b)))
                .compose(list -> eventStore.appendCorrection(list.get(1).getEventId(), "DeepChain",
                        new TopologyEvent("C", "v3", 3), t, "level 3")
                        .map(c -> {
                            List<BiTemporalEvent<TopologyEvent>> newList = new ArrayList<>(list);
                            newList.add(c);
                            return newList;
                        }))
                .compose(list -> eventStore.appendCorrection(list.get(2).getEventId(), "DeepChain",
                        new TopologyEvent("D", "v4", 4), t, "level 4")
                        .map(d -> {
                            List<BiTemporalEvent<TopologyEvent>> newList = new ArrayList<>(list);
                            newList.add(d);
                            return newList;
                        }))
                .compose(all -> {
                    // getAllVersions from the deepest leaf
                    String leafId = all.get(3).getEventId();
                    return eventStore.getAllVersions(leafId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(4, versions.size(),
                            "Deep chain: getAllVersions from leaf D must return 4 versions. "
                                    + "Got: " + versions.stream()
                                    .map(e -> "v" + e.getVersion())
                                    .collect(Collectors.joining(", ")));
                    // Verify ordering
                    for (int i = 0; i < versions.size(); i++) {
                        assertEquals(i + 1L, versions.get(i).getVersion(),
                                "Version at index " + i + " should be " + (i + 1));
                    }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Chain Model: A → B → C → D (enforced) ====================

    @Test
    @DisplayName("Chain: getAllVersions from any node returns all members")
    void chainTopologyGetAllVersionsFromLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Chain model enforced: A(v1) → B(v2) → C(v3) → D(v4)
        // Regardless of which event ID the caller passes to appendCorrection,
        // the system resolves the latest version as the predecessor.
        eventStore.appendBuilder()
                .eventType("TreeTest").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "TreeTest",
                        new TopologyEvent("B", "branch-1", 2), t, "first correction of A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getKey().getEventId(), "TreeTest",
                        new TopologyEvent("C", "branch-2", 3), t, "second correction of A")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(abc -> eventStore.appendCorrection(abc.get(1).getEventId(), "TreeTest",
                        new TopologyEvent("D", "sub-branch", 4), t, "correction of B")
                        .map(d -> {
                            List<BiTemporalEvent<TopologyEvent>> all = new ArrayList<>(abc);
                            all.add(d);
                            return all;
                        }))
                .compose(all -> {
                    // getAllVersions from D (deepest leaf, child of B)
                    String dId = all.get(3).getEventId();
                    return eventStore.getAllVersions(dId).map(versions -> Map.entry(all, versions));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TopologyEvent>> versions = result.getValue();
                    assertEquals(4, versions.size(),
                            "Chain: getAllVersions from leaf D must return all 4 family members. "
                                    + "Got: " + versions.stream()
                                    .map(e -> e.getPayload().getId() + "/v" + e.getVersion())
                                    .collect(Collectors.joining(", ")));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Chain: getAllVersions from any intermediate node returns all members")
    void chainTopologyGetAllVersionsFromSiblingBranch(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        // Chain model enforced: A(v1) → B(v2) → C(v3) → D(v4)
        eventStore.appendBuilder()
                .eventType("TreeSibling").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "TreeSibling",
                        new TopologyEvent("B", "branch-1", 2), t, "first correction of A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getKey().getEventId(), "TreeSibling",
                        new TopologyEvent("C", "branch-2", 3), t, "second correction of A")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(abc -> eventStore.appendCorrection(abc.get(1).getEventId(), "TreeSibling",
                        new TopologyEvent("D", "sub-branch", 4), t, "correction of B")
                        .map(d -> {
                            List<BiTemporalEvent<TopologyEvent>> all = new ArrayList<>(abc);
                            all.add(d);
                            return all;
                        }))
                .compose(all -> {
                    // getAllVersions from C (sibling of B, not ancestor of D)
                    String cId = all.get(2).getEventId();
                    return eventStore.getAllVersions(cId);
                })
                .onSuccess(versions -> testContext.verify(() -> {
                    assertEquals(4, versions.size(),
                            "Chain: getAllVersions from intermediate C must return all 4 family members "
                                    + "(the recursive CTE walks up to root A, then down to all descendants). "
                                    + "Got: " + versions.stream()
                                    .map(e -> e.getPayload().getId() + "/v" + e.getVersion())
                                    .collect(Collectors.joining(", ")));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Chain: getAsOfTransactionTime consistency ====================

    @Test
    @DisplayName("Chain: getAsOfTransactionTime from leaf returns latest version at time")
    void chainGetAsOfTransactionTimeFromLeaf(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ChainAsOf").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> delay(50)
                        .compose(d -> eventStore.appendCorrection(a.getEventId(), "ChainAsOf",
                                new TopologyEvent("B", "v2", 2), t, "correct A"))
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> delay(50)
                        .compose(d -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainAsOf",
                                new TopologyEvent("C", "v3", 3), t, "correct B"))
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> {
                    // getAsOfTransactionTime called with C's event ID (leaf), at now
                    // Should find the latest version in the family
                    String leafId = all.get(2).getEventId();
                    return eventStore.getAsOfTransactionTime(leafId, Instant.now());
                })
                .onSuccess(result -> testContext.verify(() -> {
                    assertNotNull(result, "getAsOfTransactionTime from chain leaf must find a result");
                    assertEquals(3L, result.getVersion(),
                            "Should return version 3 (latest) when queried at current time from leaf");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("Chain: getAsOfTransactionTime from middle returns latest visible at that time")
    void chainGetAsOfTransactionTimeFromMiddle(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ChainMidAsOf").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> delay(50)
                        .compose(d -> eventStore.appendCorrection(a.getEventId(), "ChainMidAsOf",
                                new TopologyEvent("B", "v2", 2), t, "correct A"))
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> {
                    // Record the time BEFORE creating v3
                    Instant afterV2 = Instant.now();
                    return delay(100)
                            .compose(d -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainMidAsOf",
                                    new TopologyEvent("C", "v3", 3), t, "correct B"))
                            .map(c -> Map.entry(afterV2, List.of(ab.getKey(), ab.getValue(), c)));
                })
                .compose(pair -> {
                    Instant asOfTime = pair.getKey();
                    List<BiTemporalEvent<TopologyEvent>> all = pair.getValue();
                    // Query with B's ID at a time before C was created
                    String middleId = all.get(1).getEventId();
                    return eventStore.getAsOfTransactionTime(middleId, asOfTime);
                })
                .onSuccess(result -> testContext.verify(() -> {
                    assertNotNull(result, "getAsOfTransactionTime must find a result in chain");
                    assertTrue(result.getVersion() <= 2L,
                            "At the recorded time, version C did not exist yet. "
                                    + "Expected version <= 2, got " + result.getVersion());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Consistency between getAllVersions and getAsOfTransactionTime ====================

    @Test
    @DisplayName("Chain: getAllVersions and getAsOfTransactionTime agree when called with leaf ID")
    void chainConsistencyBetweenMethods(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("ChainConsist").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> delay(50)
                        .compose(d -> eventStore.appendCorrection(a.getEventId(), "ChainConsist",
                                new TopologyEvent("B", "v2", 2), t, "correct A"))
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> delay(50)
                        .compose(d -> eventStore.appendCorrection(ab.getValue().getEventId(), "ChainConsist",
                                new TopologyEvent("C", "v3", 3), t, "correct B"))
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> {
                    String leafId = all.get(2).getEventId();
                    return Future.all(
                            eventStore.getAllVersions(leafId),
                            eventStore.getAsOfTransactionTime(leafId, Instant.now())
                    ).map(cf -> {
                        List<BiTemporalEvent<TopologyEvent>> allVersions = cf.resultAt(0);
                        BiTemporalEvent<TopologyEvent> asOfResult = cf.resultAt(1);
                        return Map.entry(allVersions, asOfResult);
                    });
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TopologyEvent>> allVersions = result.getKey();
                    BiTemporalEvent<TopologyEvent> asOfResult = result.getValue();

                    assertEquals(3, allVersions.size(),
                            "getAllVersions must find the full chain from leaf ID");
                    assertNotNull(asOfResult,
                            "getAsOfTransactionTime must find a result from leaf ID");

                    // The latest version from getAllVersions should match getAsOfTransactionTime
                    BiTemporalEvent<TopologyEvent> latestVersion = allVersions.get(allVersions.size() - 1);
                    assertEquals(latestVersion.getVersion(), asOfResult.getVersion(),
                            "Both methods must agree on the latest version");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== previous_version_id correctness ====================

    @Test
    @DisplayName("Chain: previous_version_id points to immediate predecessor, not root")
    void chainPreviousVersionIdPointsToImmediatePredecessor(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("PrevIdTest").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "PrevIdTest",
                        new TopologyEvent("B", "v2", 2), t, "correct A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getValue().getEventId(), "PrevIdTest",
                        new TopologyEvent("C", "v3", 3), t, "correct B")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> eventStore.getAllVersions(all.get(0).getEventId())
                        .map(versions -> Map.entry(all, versions)))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TopologyEvent>> created = result.getKey();
                    List<BiTemporalEvent<TopologyEvent>> versions = result.getValue();

                    // Root (v1) has no predecessor
                    assertNull(versions.get(0).getPreviousVersionId(),
                            "Root event must have null previousVersionId");

                    // B (v2) has predecessor A
                    assertEquals(created.get(0).getEventId(), versions.get(1).getPreviousVersionId(),
                            "B's previousVersionId must be A's eventId");

                    // C (v3) has predecessor B (NOT A — chain model)
                    assertEquals(created.get(1).getEventId(), versions.get(2).getPreviousVersionId(),
                            "C's previousVersionId must be B's eventId (immediate predecessor, not root)");

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Negative: closed store ====================

    @Test
    @DisplayName("getAllVersions on closed store returns failed future")
    void getAllVersionsOnClosedStoreFailsWithIllegalState(VertxTestContext testContext) throws Exception {
        eventStore.close()
                .compose(v -> eventStore.getAllVersions("any-id"))
                .onSuccess(result -> testContext.failNow("getAllVersions on closed store should fail"))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("closed"));
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime on closed store returns failed future")
    void getAsOfTransactionTimeOnClosedStoreFailsWithIllegalState(VertxTestContext testContext) throws Exception {
        eventStore.close()
                .compose(v -> eventStore.getAsOfTransactionTime("any-id", Instant.now()))
                .onSuccess(result -> testContext.failNow("getAsOfTransactionTime on closed store should fail"))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("closed"));
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Negative: null event ID ====================

    @Test
    @DisplayName("getAllVersions with null event ID throws NullPointerException")
    void getAllVersionsWithNullEventIdFails(VertxTestContext testContext) throws Exception {
        assertThrows(NullPointerException.class, () -> eventStore.getAllVersions(null));
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAllVersions with non-existent event ID returns empty list")
    void getAllVersionsWithNonExistentIdReturnsEmpty(VertxTestContext testContext) throws Exception {
        eventStore.getAllVersions("non-existent-event-id-12345")
                .onSuccess(versions -> testContext.verify(() -> {
                    assertNotNull(versions);
                    assertTrue(versions.isEmpty(),
                            "getAllVersions for non-existent ID should return empty list");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    @DisplayName("getAsOfTransactionTime with non-existent event ID returns null")
    void getAsOfTransactionTimeWithNonExistentIdReturnsNull(VertxTestContext testContext) throws Exception {
        eventStore.getAsOfTransactionTime("non-existent-event-id-12345", Instant.now())
                .onSuccess(result -> testContext.verify(() -> {
                    assertNull(result,
                            "getAsOfTransactionTime for non-existent ID should return null");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ==================== Version numbering invariant ====================

    @Test
    @DisplayName("Chain: version numbers are strictly monotonic within getAllVersions results")
    void chainVersionNumbersAreMonotonicallyIncreasing(VertxTestContext testContext) throws Exception {
        Instant t = Instant.now();

        eventStore.appendBuilder()
                .eventType("MonotonicTest").payload(new TopologyEvent("A", "root", 1)).validTime(t).execute()
                .compose(a -> eventStore.appendCorrection(a.getEventId(), "MonotonicTest",
                        new TopologyEvent("B", "v2", 2), t, "correct A")
                        .map(b -> Map.entry(a, b)))
                .compose(ab -> eventStore.appendCorrection(ab.getValue().getEventId(), "MonotonicTest",
                        new TopologyEvent("C", "v3", 3), t, "correct B")
                        .map(c -> List.of(ab.getKey(), ab.getValue(), c)))
                .compose(all -> eventStore.getAllVersions(all.get(0).getEventId()))
                .onSuccess(versions -> testContext.verify(() -> {
                    // Verify strictly increasing
                    for (int i = 1; i < versions.size(); i++) {
                        assertTrue(versions.get(i).getVersion() > versions.get(i - 1).getVersion(),
                                "Versions must be strictly increasing. "
                                        + "Version at index " + (i - 1) + " = " + versions.get(i - 1).getVersion()
                                        + ", at index " + i + " = " + versions.get(i).getVersion());
                    }
                    // Verify no duplicates
                    Set<Long> uniqueVersions = versions.stream()
                            .map(BiTemporalEvent::getVersion)
                            .collect(Collectors.toSet());
                    assertEquals(versions.size(), uniqueVersions.size(),
                            "All version numbers must be unique");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }
}
