package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for version lineage correctness in the bitemporal event store.
 *
 * <p>Covers:
 * <ul>
 *   <li>Star model invariants: corrections always point to root via previous_version_id</li>
 *   <li>Version monotonicity: each correction increments version sequentially</li>
 *   <li>getAllVersions correctness from root ID and from correction ID</li>
 *   <li>getAsOfTransactionTime with corrections</li>
 *   <li>Concurrent correction serialization (advisory lock + unique index)</li>
 *   <li>Negative: appendCorrection with invalid originalEventId</li>
 *   <li>Negative: null/empty parameters rejected</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class VersionLineageIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(VersionLineageIntegrationTest.class);

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_version_lineage_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private Vertx vertx;
    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;

    @BeforeAll
    static void initSchema() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, SchemaComponent.BITEMPORAL);
        testContext.completeNow();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> chain = Future.succeededFuture();

        if (eventStore != null) {
            try {
                eventStore.close();
            } catch (Exception e) {
                logger.warn("Error closing event store: {}", e.getMessage());
            }
            eventStore = null;
        }

        if (peeGeeQManager != null) {
            chain = peeGeeQManager.closeReactive()
                .recover(err -> {
                    logger.warn("Error closing PeeGeeQManager: {}", err.getMessage());
                    return Future.succeededFuture();
                });
            peeGeeQManager = null;
        }

        chain.onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Creates the PeeGeeQManager and event store. Returns a Future that completes
     * when the manager is started and the event store is ready.
     * Uses the explicit PeeGeeQConfiguration constructor to avoid system property races.
     */
    private Future<PgBiTemporalEventStore<Map<String, Object>>> startManagerAndStore() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
            "development",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName(),
            postgres.getUsername(),
            postgres.getPassword(),
            "public");
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        return peeGeeQManager.start()
            .map(v -> {
                eventStore = new PgBiTemporalEventStore<>(
                    vertx, peeGeeQManager, mapClass(), "bitemporal_event_log", new ObjectMapper());
                return eventStore;
            });
    }

    // ========== POSITIVE: Star model invariants ==========

    /**
     * Single correction produces version 2 pointing back to the root event.
     */
    @Test
    void singleCorrectionCreatesVersion2WithRootAsParent(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("price.updated")
                .payload(Map.of("price", 100))
                .validTime(Instant.now())
                .aggregateId("product-1")
                .execute()
                .compose(original -> store.appendCorrection(
                        original.getEventId(), "price.updated",
                        Map.of("price", 110), Instant.now(), "Price was wrong")
                    .map(correction -> Map.entry(original, correction))))
            .onSuccess(pair -> testContext.verify(() -> {
                BiTemporalEvent<Map<String, Object>> original = pair.getKey();
                BiTemporalEvent<Map<String, Object>> correction = pair.getValue();

                assertEquals(1L, original.getVersion());
                assertNull(original.getPreviousVersionId());
                assertFalse(original.isCorrection());

                assertEquals(2L, correction.getVersion());
                assertEquals(original.getEventId(), correction.getPreviousVersionId());
                assertTrue(correction.isCorrection());
                assertEquals("Price was wrong", correction.getCorrectionReason());
                assertNotEquals(original.getEventId(), correction.getEventId());

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * Multiple sequential corrections produce monotonically increasing versions,
     * all pointing to the same root (star model).
     */
    @Test
    void multipleCorrectionsMaintainMonotonicVersionsAndStarModel(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("balance.adjusted")
                .payload(Map.of("balance", 1000))
                .validTime(Instant.now())
                .aggregateId("account-42")
                .execute()
                .compose(original -> {
                    String rootId = original.getEventId();
                    return store.appendCorrection(rootId, "balance.adjusted",
                            Map.of("balance", 1050), Instant.now(), "Correction 1")
                        .compose(c1 -> store.appendCorrection(rootId, "balance.adjusted",
                                Map.of("balance", 1075), Instant.now(), "Correction 2"))
                        .compose(c2 -> store.appendCorrection(rootId, "balance.adjusted",
                                Map.of("balance", 1100), Instant.now(), "Correction 3"))
                        .map(c3 -> rootId);
                })
                .compose(rootId -> store.getAllVersions(rootId)))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(4, versions.size(), "Should have root + 3 corrections");

                String rootId = versions.get(0).getEventId();
                for (int i = 0; i < versions.size(); i++) {
                    BiTemporalEvent<Map<String, Object>> v = versions.get(i);
                    assertEquals(i + 1L, v.getVersion(), "Version " + (i + 1) + " should be sequential");

                    if (i == 0) {
                        assertNull(v.getPreviousVersionId(), "Root has no parent");
                        assertFalse(v.isCorrection());
                    } else {
                        assertEquals(rootId, v.getPreviousVersionId(),
                            "Version " + (i + 1) + " must point to root (star model)");
                        assertTrue(v.isCorrection());
                    }
                }

                // All event_ids must be unique
                Set<String> ids = new HashSet<>();
                for (BiTemporalEvent<Map<String, Object>> v : versions) {
                    assertTrue(ids.add(v.getEventId()), "Duplicate event_id: " + v.getEventId());
                }

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== POSITIVE: getAllVersions from non-root ID ==========

    /**
     * getAllVersions called with a correction's event ID (not the root)
     * must still return the complete version family.
     */
    @Test
    void getAllVersionsFromCorrectionIdReturnsFullFamily(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("trade.executed")
                .payload(Map.of("shares", 100))
                .validTime(Instant.now())
                .aggregateId("trade-99")
                .execute()
                .compose(original -> {
                    String rootId = original.getEventId();
                    return store.appendCorrection(rootId, "trade.executed",
                            Map.of("shares", 150), Instant.now(), "Wrong quantity")
                        .compose(c1 -> store.appendCorrection(rootId, "trade.executed",
                                Map.of("shares", 200), Instant.now(), "Further adjustment")
                            .map(c2 -> Map.entry(c1, c2)));
                })
                .compose(corrections -> {
                    String correctionId = corrections.getKey().getEventId();
                    return store.getAllVersions(correctionId);
                }))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(3, versions.size(),
                    "getAllVersions from a correction ID must return complete family (root + 2 corrections)");
                assertEquals(1L, versions.get(0).getVersion());
                assertEquals(2L, versions.get(1).getVersion());
                assertEquals(3L, versions.get(2).getVersion());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * getAllVersions called with the root event ID returns the same family.
     */
    @Test
    void getAllVersionsFromRootIdReturnsFullFamily(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("order.placed")
                .payload(Map.of("items", 3))
                .validTime(Instant.now())
                .aggregateId("order-77")
                .execute()
                .compose(original -> store.appendCorrection(
                        original.getEventId(), "order.placed",
                        Map.of("items", 5), Instant.now(), "Item count wrong")
                    .map(c -> original.getEventId()))
                .compose(rootId -> store.getAllVersions(rootId)))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(2, versions.size());
                assertEquals(1L, versions.get(0).getVersion());
                assertEquals(2L, versions.get(1).getVersion());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * getAllVersions for a standalone event (no corrections) returns a single-element list.
     */
    @Test
    void getAllVersionsForUncorrectedEventReturnsSingleVersion(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("standalone.event")
                .payload(Map.of("data", "solo"))
                .validTime(Instant.now())
                .execute()
                .compose(event -> store.getAllVersions(event.getEventId())))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(1, versions.size());
                assertEquals(1L, versions.get(0).getVersion());
                assertNull(versions.get(0).getPreviousVersionId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== POSITIVE: getAsOfTransactionTime with corrections ==========

    /**
     * getAsOfTransactionTime returns the correct version at a given point in time.
     * Uses actual DB-recorded transaction_time to avoid Java/PostgreSQL clock skew.
     */
    @Test
    void getAsOfTransactionTimeReturnsCorrectVersionAtPointInTime(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("rate.published")
                .payload(Map.of("rate", 1.25))
                .validTime(Instant.now())
                .aggregateId("rate-USD")
                .execute()
                .compose(original -> {
                    // Delay to create a clear gap between original and correction transaction times
                    Promise<BiTemporalEvent<Map<String, Object>>> delayed = Promise.promise();
                    peeGeeQManager.getVertx().setTimer(200, id -> delayed.complete(original));
                    return delayed.future();
                })
                .compose(original -> store.appendCorrection(
                        original.getEventId(), "rate.published",
                        Map.of("rate", 1.30), Instant.now(), "Rate recalculated")
                    .map(correction -> Map.entry(original, correction)))
                .compose(pair -> {
                    BiTemporalEvent<Map<String, Object>> original = pair.getKey();
                    BiTemporalEvent<Map<String, Object>> correction = pair.getValue();

                    // Use a time between the two DB-recorded transaction times
                    Instant originalTxTime = original.getTransactionTime();
                    Instant correctionTxTime = correction.getTransactionTime();
                    Instant midpoint = originalTxTime.plusMillis(
                        (correctionTxTime.toEpochMilli() - originalTxTime.toEpochMilli()) / 2);
                    Instant afterAll = correctionTxTime.plusMillis(1);

                    return store.getAsOfTransactionTime(original.getEventId(), midpoint)
                        .compose(asOfMid -> store.getAsOfTransactionTime(original.getEventId(), afterAll)
                            .map(asOfAfter -> Map.entry(asOfMid, asOfAfter)));
                }))
            .onSuccess(pair -> testContext.verify(() -> {
                BiTemporalEvent<Map<String, Object>> asOfBetween = pair.getKey();
                BiTemporalEvent<Map<String, Object>> asOfAfter = pair.getValue();

                assertNotNull(asOfBetween, "Should find an event as-of time between original and correction");
                assertEquals(1L, asOfBetween.getVersion(), "As-of time before correction should return version 1");

                assertNotNull(asOfAfter, "Should find an event as-of time after correction");
                assertEquals(2L, asOfAfter.getVersion(), "As-of time after correction should return version 2");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== POSITIVE: Concurrent corrections produce unique versions ==========

    /**
     * Fire multiple corrections concurrently for the same root event.
     * All must succeed with distinct, sequential version numbers.
     * This verifies the advisory lock prevents the read-modify-write race.
     */
    @Test
    void concurrentCorrectionsProduceUniqueVersions(VertxTestContext testContext) throws Exception {
        int concurrentCorrections = 5;

        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("inventory.counted")
                .payload(Map.of("count", 100))
                .validTime(Instant.now())
                .aggregateId("warehouse-1")
                .execute()
                .compose(original -> {
                    String rootId = original.getEventId();

                    List<Future<BiTemporalEvent<Map<String, Object>>>> futures = new ArrayList<>();
                    for (int i = 0; i < concurrentCorrections; i++) {
                        final int idx = i;
                        futures.add(store.appendCorrection(
                            rootId, "inventory.counted",
                            Map.of("count", 100 + idx + 1),
                            Instant.now(),
                            "Concurrent correction " + (idx + 1)));
                    }

                    return Future.all(futures).map(cf -> rootId);
                })
                .compose(rootId -> store.getAllVersions(rootId)))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(1 + concurrentCorrections, versions.size(),
                    "Should have root + " + concurrentCorrections + " corrections");

                // Versions must be 1, 2, 3, 4, 5, 6 — no duplicates, no gaps
                Set<Long> versionNumbers = new HashSet<>();
                for (BiTemporalEvent<Map<String, Object>> v : versions) {
                    assertTrue(versionNumbers.add(v.getVersion()),
                        "Duplicate version number: " + v.getVersion());
                }

                for (long expected = 1; expected <= 1 + concurrentCorrections; expected++) {
                    assertTrue(versionNumbers.contains(expected),
                        "Missing version " + expected + " — versions present: " + versionNumbers);
                }

                // All corrections must point to the root (star model)
                String rootId = versions.get(0).getEventId();
                for (int i = 1; i < versions.size(); i++) {
                    assertEquals(rootId, versions.get(i).getPreviousVersionId(),
                        "Correction " + i + " must point to root");
                }

                logger.info("Concurrent corrections test passed: {} versions with no duplicates", versions.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== POSITIVE: Correction metadata preserved ==========

    /**
     * Correction preserves correlationId, aggregateId, headers, and correctionReason.
     */
    @Test
    void correctionPreservesAllMetadata(VertxTestContext testContext) throws Exception {
        String correlationId = "corr-" + System.nanoTime();
        String aggregateId = "agg-metadata-test";

        startManagerAndStore()
            .compose(store -> store.appendBuilder()
                .eventType("metadata.event")
                .payload(Map.of("v", 1))
                .validTime(Instant.now())
                .aggregateId(aggregateId)
                .correlationId(correlationId)
                .execute()
                .compose(original -> store.appendCorrection(
                        original.getEventId(), "metadata.event",
                        Map.of("v", 2), Instant.now(),
                        Map.of("source", "test", "region", "us-east"),
                        correlationId, aggregateId,
                        "Metadata correction test")))
            .onSuccess(correction -> testContext.verify(() -> {
                assertEquals(correlationId, correction.getCorrelationId());
                assertEquals(aggregateId, correction.getAggregateId());
                assertTrue(correction.isCorrection());
                assertEquals("Metadata correction test", correction.getCorrectionReason());
                assertEquals(2L, correction.getVersion());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== POSITIVE: Independent event families don't interfere ==========

    /**
     * Corrections on different root events don't interfere with each other's versioning.
     */
    @Test
    void independentEventFamiliesHaveIndependentVersioning(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> {
                Future<BiTemporalEvent<Map<String, Object>>> family1 = store.appendBuilder()
                    .eventType("family1.event")
                    .payload(Map.of("family", 1))
                    .validTime(Instant.now())
                    .execute();

                Future<BiTemporalEvent<Map<String, Object>>> family2 = store.appendBuilder()
                    .eventType("family2.event")
                    .payload(Map.of("family", 2))
                    .validTime(Instant.now())
                    .execute();

                return Future.all(family1, family2)
                    .compose(cf -> {
                        BiTemporalEvent<Map<String, Object>> root1 = cf.resultAt(0);
                        BiTemporalEvent<Map<String, Object>> root2 = cf.resultAt(1);

                        return store.appendCorrection(root1.getEventId(), "family1.event",
                                Map.of("family", 1, "corrected", true), Instant.now(), "Fix family 1")
                            .compose(c1 -> store.appendCorrection(root2.getEventId(), "family2.event",
                                    Map.of("family", 2, "corrected", true), Instant.now(), "Fix family 2")
                                .map(c2 -> Map.entry(root1.getEventId(), root2.getEventId())));
                    })
                    .compose(rootIds -> {
                        Future<List<BiTemporalEvent<Map<String, Object>>>> f1Versions =
                            store.getAllVersions(rootIds.getKey());
                        Future<List<BiTemporalEvent<Map<String, Object>>>> f2Versions =
                            store.getAllVersions(rootIds.getValue());
                        return Future.all(f1Versions, f2Versions);
                    });
            })
            .onSuccess(cf -> testContext.verify(() -> {
                List<BiTemporalEvent<Map<String, Object>>> f1 = cf.resultAt(0);
                List<BiTemporalEvent<Map<String, Object>>> f2 = cf.resultAt(1);

                assertEquals(2, f1.size(), "Family 1 should have root + 1 correction");
                assertEquals(2, f2.size(), "Family 2 should have root + 1 correction");

                // Both families should have versions 1, 2 independently
                assertEquals(1L, f1.get(0).getVersion());
                assertEquals(2L, f1.get(1).getVersion());
                assertEquals(1L, f2.get(0).getVersion());
                assertEquals(2L, f2.get(1).getVersion());

                // Event IDs must not cross families
                Set<String> f1Ids = new HashSet<>();
                for (BiTemporalEvent<Map<String, Object>> e : f1) f1Ids.add(e.getEventId());
                for (BiTemporalEvent<Map<String, Object>> e : f2) {
                    assertFalse(f1Ids.contains(e.getEventId()),
                        "Family 2 event appeared in family 1 results");
                }

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== NEGATIVE: Parameter validation ==========

    /**
     * appendCorrection rejects null originalEventId.
     */
    @Test
    void appendCorrectionRejectsNullOriginalEventId(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .onSuccess(store -> testContext.verify(() -> {
                NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> store.appendCorrection(null, "test.type",
                        Map.of("data", 1), Instant.now(), "reason"));
                assertTrue(ex.getMessage().contains("Original event ID"),
                    "Message should mention original event ID: " + ex.getMessage());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * appendCorrection rejects null correctionReason.
     */
    @Test
    void appendCorrectionRejectsNullCorrectionReason(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .onSuccess(store -> testContext.verify(() -> {
                NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> store.appendCorrection("some-id", "test.type",
                        Map.of("data", 1), Instant.now(), (String) null));
                assertTrue(ex.getMessage().contains("Correction reason"),
                    "Message should mention correction reason: " + ex.getMessage());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * appendCorrection rejects null payload.
     */
    @Test
    void appendCorrectionRejectsNullPayload(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .onSuccess(store -> testContext.verify(() -> {
                NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> store.appendCorrection("some-id", "test.type",
                        null, Instant.now(), "reason"));
                assertTrue(ex.getMessage().contains("Payload"),
                    "Message should mention payload: " + ex.getMessage());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * appendCorrection for a non-existent originalEventId is rejected by the database.
     * The check constraint chk_previous_version requires (version=1, previous_version_id IS NULL)
     * but a correction always sets previous_version_id. Since no prior versions exist,
     * MAX(version) is 0, so nextVersion=1, which violates the constraint.
     */
    @Test
    void appendCorrectionForNonExistentRootIsRejectedByConstraint(VertxTestContext testContext) throws Exception {
        String fakeRootId = "non-existent-" + System.nanoTime();

        startManagerAndStore()
            .compose(store -> store.appendCorrection(fakeRootId, "orphan.correction",
                    Map.of("orphan", true), Instant.now(), "Correcting phantom event"))
            .onSuccess(event -> testContext.verify(() ->
                fail("Should have been rejected by chk_previous_version constraint")))
            .onFailure(err -> testContext.verify(() -> {
                assertTrue(err.getMessage().contains("chk_previous_version"),
                    "Expected chk_previous_version constraint violation, got: " + err.getMessage());
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    // ========== NEGATIVE: getAllVersions for non-existent event ==========

    /**
     * getAllVersions for an event ID that doesn't exist returns an empty list.
     */
    @Test
    void getAllVersionsForNonExistentEventReturnsEmptyList(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .compose(store -> store.getAllVersions("does-not-exist-" + System.nanoTime()))
            .onSuccess(versions -> testContext.verify(() -> {
                assertNotNull(versions);
                assertTrue(versions.isEmpty(), "Should return empty list for non-existent event");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    /**
     * getAllVersions rejects null eventId.
     */
    @Test
    void getAllVersionsRejectsNullEventId(VertxTestContext testContext) throws Exception {
        startManagerAndStore()
            .onSuccess(store -> testContext.verify(() -> {
                assertThrows(NullPointerException.class, () -> store.getAllVersions(null));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

}
