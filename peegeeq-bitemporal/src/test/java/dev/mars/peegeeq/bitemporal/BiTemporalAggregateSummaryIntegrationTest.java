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
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.setup.SqlTemplateProcessor;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the opt-in aggregate summary query path (I4):
 * parity between the live event-log GROUP BY and the trigger-maintained
 * summary table, and the AggregateSource override semantics.
 *
 * The summary table + trigger are created by applying the production
 * "eventstore-aggregate-summary" SQL template via {@link SqlTemplateProcessor}
 * — the same mechanism the setup service uses — so there is no DDL drift
 * between this test and production.
 */
@Tag(TestCategories.CORE)
@Testcontainers
@ExtendWith(VertxExtension.class)
class BiTemporalAggregateSummaryIntegrationTest {

    private static final String TABLE_NAME = "bitemporal_event_log";

    /** Schema is the instance anchor — resolve it the same way the rest of the suite does. */
    private String resolveSchema() {
        // Explicit schema - PeeGeeQ has no default schema and no ambient configuration
        return PostgreSQLTestConstants.TEST_SCHEMA;
    }

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = PostgreSQLTestConstants.createStandardContainer();
        container.withDatabaseName("peegeeq_summary_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    public static class TestEvent {
        private final String id;

        @JsonCreator
        public TestEvent(@JsonProperty("id") String id) {
            this.id = id;
        }

        public String getId() { return id; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return Objects.equals(id, ((TestEvent) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private Vertx vertx;
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> summaryStore;
    private PgBiTemporalEventStore<TestEvent> liveStore;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        String schema = resolveSchema();

        // The schema is the instance anchor: it must reach the manager configuration
        // (search_path of every pool), not just the DDL placement below. Note: most of
        // the suite resolves the schema for DDL only and leaves the manager on the
        // builder's default schema — a latent gap under custom-schema runs.
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(schema)
                .property("peegeeq.health-check.enabled", "false")
                .property("peegeeq.health-check.queue-checks-enabled", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .property("peegeeq.queue.recovery.enabled", "false")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.BITEMPORAL);

        vertx = Vertx.vertx();

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
        Pool setupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        // Apply the production summary template (same mechanism as the setup service),
        // then reset both tables for test isolation. The setup pool has no search_path
        // configured, so every statement here is explicitly schema-qualified.
        SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();
        Map<String, String> templateParams = Map.of(
                "tableName", TABLE_NAME,
                "schema", schema,
                "notificationPrefix", "test_");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());

        setupPool.withConnection(conn ->
                        // The trigger/function/table are non-idempotent by design (created once per
                        // store, like the eventstore template's own notify trigger) — drop before
                        // re-applying so each test starts from a clean install.
                        conn.query("DROP TRIGGER IF EXISTS \"trigger_" + TABLE_NAME + "_aggregate_summary\" ON " + schema + "." + TABLE_NAME).execute()
                                .compose(v -> conn.query("DROP FUNCTION IF EXISTS " + schema + ".\"maintain_" + TABLE_NAME + "_aggregate_summary\"() CASCADE").execute())
                                .compose(v -> conn.query("DROP TABLE IF EXISTS " + schema + "." + TABLE_NAME + "_aggregate_summary CASCADE").execute())
                                .compose(v -> templateProcessor.applyTemplate(conn, "eventstore-aggregate-summary", templateParams))
                                .compose(v -> conn.query("TRUNCATE TABLE " + schema + "." + TABLE_NAME + " CASCADE").execute())
                                .compose(v -> conn.query("TRUNCATE TABLE " + schema + "." + TABLE_NAME + "_aggregate_summary").execute()))
                .compose(v -> setupPool.close())
                .compose(v -> manager.start())
                .onSuccess(v -> {
                    factory = new BiTemporalEventStoreFactory(vertx, manager);
                    summaryStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class,
                            new EventStoreConfig.Builder()
                                    .eventStoreName("summary-store")
                                    .tableName(TABLE_NAME)
                                    .aggregateSummaryEnabled(true)
                                    .build());
                    liveStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class, TABLE_NAME);
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
        Future<Void> closeFuture = (summaryStore != null) ? summaryStore.close() : Future.succeededFuture();
        closeFuture
                .compose(v -> liveStore != null ? liveStore.close() : Future.succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .compose(v -> vertx != null ? vertx.close() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /** Appends the shared fixture: agg-A (TypeA x2, TypeB x1), agg-B (TypeA x1). */
    private Future<Void> seedEvents() {
        Instant base = Instant.now();
        return summaryStore.appendBuilder().eventType("TypeA").payload(new TestEvent("a1"))
                .validTime(base.minusSeconds(30)).aggregateId("agg-A").execute()
                .compose(v -> summaryStore.appendBuilder().eventType("TypeA").payload(new TestEvent("a2"))
                        .validTime(base.minusSeconds(20)).aggregateId("agg-A").execute())
                .compose(v -> summaryStore.appendBuilder().eventType("TypeB").payload(new TestEvent("b1"))
                        .validTime(base.minusSeconds(10)).aggregateId("agg-A").execute())
                .compose(v -> summaryStore.appendBuilder().eventType("TypeA").payload(new TestEvent("c1"))
                        .validTime(base.minusSeconds(5)).aggregateId("agg-B").execute())
                .mapEmpty();
    }

    /** Asserts both results are identical in shape and content (eventTypes compared as sets). */
    private void assertParity(EventStore.AggregateListResult summary, EventStore.AggregateListResult live) {
        assertEquals(live.getTotalCount(), summary.getTotalCount(), "totalCount must match");
        assertEquals(live.getCount(), summary.getCount(), "count must match");
        assertEquals(live.isTruncated(), summary.isTruncated(), "truncated must match");
        assertEquals(live.getLimit(), summary.getLimit());
        assertEquals(live.getOffset(), summary.getOffset());

        for (int i = 0; i < live.getAggregates().size(); i++) {
            EventStore.AggregateInfo l = live.getAggregates().get(i);
            EventStore.AggregateInfo s = summary.getAggregates().get(i);
            assertEquals(l.getAggregateId(), s.getAggregateId(), "aggregate order/id must match at index " + i);
            assertEquals(l.getEventCount(), s.getEventCount(), "eventCount must match for " + l.getAggregateId());
            assertEquals(l.getFirstEventTime(), s.getFirstEventTime(), "firstEventTime must match for " + l.getAggregateId());
            assertEquals(l.getLastEventTime(), s.getLastEventTime(), "lastEventTime must match for " + l.getAggregateId());
            assertEquals(new HashSet<>(l.getEventTypes()), new HashSet<>(s.getEventTypes()),
                    "eventTypes must match for " + l.getAggregateId());
        }
    }

    @Test
    void testSummaryAndEventLogParityUnfiltered(VertxTestContext testContext) throws Exception {
        seedEvents()
                // AUTO on a summary-enabled store reads the summary table
                .compose(v -> summaryStore.getUniqueAggregates(null, 10, 0))
                .compose(summaryResult -> summaryStore
                        .getUniqueAggregates(null, 10, 0, EventStore.AggregateSource.EVENT_LOG)
                        .map(liveResult -> Map.entry(summaryResult, liveResult)))
                .onComplete(testContext.succeeding(results -> testContext.verify(() -> {
                    assertEquals(2, results.getValue().getCount(), "Fixture must produce 2 aggregates");
                    assertParity(results.getKey(), results.getValue());
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testSummaryAndEventLogParityWithEventTypeFilter(VertxTestContext testContext) throws Exception {
        seedEvents()
                .compose(v -> summaryStore.getUniqueAggregates("TypeA", 10, 0))
                .compose(summaryResult -> summaryStore
                        .getUniqueAggregates("TypeA", 10, 0, EventStore.AggregateSource.EVENT_LOG)
                        .map(liveResult -> Map.entry(summaryResult, liveResult)))
                .onComplete(testContext.succeeding(results -> testContext.verify(() -> {
                    // The per-(aggregate_id, event_type) keying exists exactly for this case:
                    // agg-A has 3 events total but only 2 of TypeA — both sources must agree on 2.
                    var live = results.getValue();
                    var aggA = live.getAggregates().stream()
                            .filter(a -> a.getAggregateId().equals("agg-A")).findFirst().orElseThrow();
                    assertEquals(2L, aggA.getEventCount(),
                            "Filtered eventCount must cover only the filtered type's events");
                    assertParity(results.getKey(), live);
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testSummarySourceFailsWhenNotEnabled(VertxTestContext testContext) throws Exception {
        liveStore.getUniqueAggregates(null, 10, 0, EventStore.AggregateSource.SUMMARY)
                .onComplete(testContext.failing(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("aggregateSummaryEnabled"),
                            "Error must name the option, got: " + err.getMessage());
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testVerifyReportsDriftAndRebuildRepairsIt(VertxTestContext testContext) throws Exception {
        seedEvents()
                .compose(v -> summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.VERIFY))
                .compose(clean -> {
                    // Fixture pairs: (agg-A,TypeA), (agg-A,TypeB), (agg-B,TypeA)
                    assertEquals(EventStore.ReconcileMode.VERIFY, clean.getMode());
                    assertEquals(3, clean.getAggregatesChecked(), "All live pairs must be checked");
                    assertEquals(0, clean.getMissingInSummary(), "Trigger-maintained summary must be clean");
                    assertEquals(0, clean.getStaleInSummary());
                    assertEquals(0, clean.getOrphanedInSummary());

                    // Tamper directly: corrupt a count, plant an orphan, delete a row
                    return manager.getPool().query(
                                    "UPDATE bitemporal_event_log_aggregate_summary SET event_count = 99 " +
                                            "WHERE aggregate_id = 'agg-A' AND event_type = 'TypeA'").execute()
                            .compose(r -> manager.getPool().query(
                                    "INSERT INTO bitemporal_event_log_aggregate_summary VALUES " +
                                            "('ghost', 'TypeX', 5, NOW(), NOW())").execute())
                            .compose(r -> manager.getPool().query(
                                    "DELETE FROM bitemporal_event_log_aggregate_summary " +
                                            "WHERE aggregate_id = 'agg-B'").execute());
                })
                .compose(v -> summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.VERIFY))
                .compose(drift -> {
                    assertEquals(1, drift.getStaleInSummary(), "The corrupted count must be reported stale");
                    assertEquals(1, drift.getOrphanedInSummary(), "The planted ghost row must be reported orphaned");
                    assertEquals(1, drift.getMissingInSummary(), "The deleted row must be reported missing");
                    assertFalse(drift.getSampleMismatches().isEmpty(), "Mismatch samples must be provided");

                    return summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.REBUILD);
                })
                .compose(rebuild -> {
                    assertEquals(EventStore.ReconcileMode.REBUILD, rebuild.getMode());
                    assertTrue(rebuild.getRepaired() >= 4,
                            "Rebuild must upsert all 3 live pairs and delete the ghost, got: " + rebuild.getRepaired());
                    return summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.VERIFY);
                })
                .onComplete(testContext.succeeding(afterRebuild -> testContext.verify(() -> {
                    assertEquals(0, afterRebuild.getMissingInSummary(), "Verify after rebuild must be clean");
                    assertEquals(0, afterRebuild.getStaleInSummary(), "Verify after rebuild must be clean");
                    assertEquals(0, afterRebuild.getOrphanedInSummary(), "Verify after rebuild must be clean");
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testEnableOnExistingStoreViaInstallThenRebuild(VertxTestContext testContext) throws Exception {
        // Simulate a store that predates the summary option: remove the summary artifacts,
        // seed events with NO trigger installed, then run the documented enablement sequence:
        // txn A — install table + trigger (template); gap event appended (trigger now live);
        // txn B — rebuild fills all history. Verify must then report clean.
        SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();
        // The manager pool's search_path is the configured schema, so statements on it are
        // unqualified; the template (DDL) is explicitly schema-parameterized.
        Map<String, String> templateParams = Map.of(
                "tableName", TABLE_NAME,
                "schema", resolveSchema(),
                "notificationPrefix", "test_");

        manager.getPool().query("DROP TRIGGER IF EXISTS \"trigger_" + TABLE_NAME + "_aggregate_summary\" ON " + TABLE_NAME).execute()
                .compose(v -> manager.getPool().query("DROP FUNCTION IF EXISTS \"maintain_" + TABLE_NAME + "_aggregate_summary\"() CASCADE").execute())
                .compose(v -> manager.getPool().query("DROP TABLE IF EXISTS " + TABLE_NAME + "_aggregate_summary CASCADE").execute())
                // Events that predate the summary (no trigger fires for these)
                .compose(v -> seedEvents())
                // Txn A: install table + trigger via the production template
                .compose(v -> manager.getPool().withConnection(conn ->
                        templateProcessor.applyTemplate(conn, "eventstore-aggregate-summary", templateParams)))
                // Gap event between install and rebuild — maintained by the now-live trigger
                .compose(v -> summaryStore.appendBuilder().eventType("TypeA").payload(new TestEvent("gap"))
                        .validTime(Instant.now()).aggregateId("agg-GAP").execute())
                // Txn B: rebuild fills the pre-trigger history (and must not corrupt the gap event's row)
                .compose(v -> summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.REBUILD))
                .compose(v -> summaryStore.reconcileAggregateSummary(EventStore.ReconcileMode.VERIFY))
                .compose(verify -> {
                    assertEquals(0, verify.getMissingInSummary(), "Rebuild must cover pre-trigger events");
                    assertEquals(0, verify.getStaleInSummary(), "Gap event's trigger row must not be corrupted");
                    assertEquals(0, verify.getOrphanedInSummary());
                    assertEquals(4, verify.getAggregatesChecked(),
                            "3 seeded pairs plus the gap event's pair must all be present");

                    // Final proof: both query sources agree
                    return summaryStore.getUniqueAggregates(null, 10, 0)
                            .compose(summaryResult -> summaryStore
                                    .getUniqueAggregates(null, 10, 0, EventStore.AggregateSource.EVENT_LOG)
                                    .map(liveResult -> Map.entry(summaryResult, liveResult)));
                })
                .onComplete(testContext.succeeding(results -> testContext.verify(() -> {
                    assertParity(results.getKey(), results.getValue());
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testReconcileFailsWhenSummaryNotEnabled(VertxTestContext testContext) throws Exception {
        liveStore.reconcileAggregateSummary(EventStore.ReconcileMode.VERIFY)
                .onComplete(testContext.failing(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("aggregateSummaryEnabled"),
                            "Error must name the option, got: " + err.getMessage());
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testEventLogOverrideWorksOnSummaryEnabledStore(VertxTestContext testContext) throws Exception {
        seedEvents()
                .compose(v -> summaryStore.getUniqueAggregates(null, 10, 0, EventStore.AggregateSource.EVENT_LOG))
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertEquals(2, result.getCount(),
                            "EVENT_LOG override must run the live query even on a summary-enabled store");
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
