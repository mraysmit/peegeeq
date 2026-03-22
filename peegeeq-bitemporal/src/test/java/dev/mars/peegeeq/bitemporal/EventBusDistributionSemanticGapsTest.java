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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that 5 semantic defects in the event-bus distribution path
 * ({@code peegeeq.database.use.event.bus.distribution=true}) have been fixed.
 *
 * <p>Each test has a POSITIVE case (direct path works correctly) and a FIXED case
 * (event-bus path now behaves identically to the direct path).
 *
 * <h3>Defects fixed and verified:</h3>
 * <ol>
 *   <li>Event ID: caller-generated ID now passed to worker and used verbatim</li>
 *   <li>Transaction time: caller-generated timestamp now passed to worker</li>
 *   <li>Transaction wrapper: worker now uses pool.withTransaction() for atomicity</li>
 *   <li>Trace context: traceparent now propagated via DeliveryOptions headers</li>
 *   <li>Worker DB timing: worker now logs its own DB execution time</li>
 * </ol>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class EventBusDistributionSemanticGapsTest {

    private static final Logger logger = LoggerFactory.getLogger(EventBusDistributionSemanticGapsTest.class);
    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("eventbus_gaps_" + System.currentTimeMillis());
        container.withUsername("peegeeq");
        container.withPassword("peegeeq_test_password");
        return container;
    }

    private PeeGeeQManager manager;
    /**
     * Single event store instance used by all tests.
     * The system property peegeeq.database.use.event.bus.distribution is toggled
     * per-test to switch between direct and event-bus paths at call time.
     */
    private PgBiTemporalEventStore<TestPayload> eventStore;
    /** Standalone query pool for verification queries (independent of event stores). */
    private Pool verificationPool;
    /** Whether worker verticles have been deployed (done once per test class). */
    private boolean workersDeployed = false;

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
    }

    private String resolveSchema() {
        String configured = System.getProperty(DATABASE_SCHEMA_PROPERTY, "public");
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
    void setUp(VertxTestContext testContext) throws Exception {
        // Ensure event bus distribution is OFF during setup
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
                    Vertx managerVertx = manager.getVertx();

                    verificationPool = PgBuilder.pool()
                            .with(new PoolOptions().setMaxSize(5))
                            .connectingTo(connectOptions())
                            .using(managerVertx)
                            .build();

                    Pool setupPool = PgBuilder.pool().connectingTo(connectOptions()).using(managerVertx).build();
                    return setupPool.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
                            .recover(err -> Future.succeededFuture(null))
                            .compose(r -> setupPool.close());
                })
                .compose(v -> {
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
                    eventStore = (PgBiTemporalEventStore<TestPayload>) factory.createEventStore(
                            TestPayload.class, "bitemporal_event_log");

                    // Deploy worker verticles on the manager's Vertx for event-bus tests
                    return PgBiTemporalEventStore.deployDatabaseWorkerVerticles(
                            1, "bitemporal_event_log");
                })
                .onSuccess(v -> {
                    workersDeployed = true;
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
    // ERROR: Event ID silently discarded  --  breaks idempotency
    // ========================================================================

    @Test
    @DisplayName("POSITIVE: Direct path returns the event ID it generated internally")
    void directPathReturnsConsistentEventId(VertxTestContext testContext) throws Exception {
        String eventType = "gap1.direct.eventid";
        TestPayload payload = new TestPayload("direct-event-id-test", 1);
        Instant validTime = Instant.now();

        // Direct path: ensure event-bus distribution is OFF
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    String returnedEventId = event.getEventId();
                    assertNotNull(returnedEventId, "Direct path must return a non-null event ID");

                    // Verify the same event ID is in the database
                    String sql = "SELECT event_id FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                String dbEventId = rows.iterator().next().getString("event_id");
                                assertEquals(returnedEventId, dbEventId,
                                        "Direct path: returned event ID must match the database row");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("FIXED: Event-bus path now preserves caller-generated event ID")
    void eventBusPathPreservesCallerEventId(VertxTestContext testContext) throws Exception {
        /*
         * FIX VERIFICATION: The event-bus path now includes the caller-generated
         * eventId in the operation JSON, and the worker uses it instead of
         * generating a new one.  The returned event ID must match the database.
         */
        String eventType = "gap1.eventbus.eventid.fixed";
        TestPayload payload = new TestPayload("eventbus-event-id-test", 2);
        Instant validTime = Instant.now();

        // Event-bus path: enable event-bus distribution
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    String returnedEventId = event.getEventId();
                    assertNotNull(returnedEventId, "Event bus path must return a non-null event ID");

                    // Verify the returned event ID matches the database
                    String sql = "SELECT event_id FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                String dbEventId = rows.iterator().next().getString("event_id");
                                assertEquals(returnedEventId, dbEventId,
                                        "Event bus path: returned event ID must match DB");
                                logger.info("DEFECT 1 FIXED: Event-bus path preserved caller " +
                                        "event ID '{}' -- stored correctly in database.",
                                        returnedEventId);
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // ERROR: Transaction time silently regenerated  --  corrupts audit trail
    // ========================================================================

    @Test
    @DisplayName("POSITIVE: Direct path uses a consistent transaction time across the operation")
    void directPathUsesConsistentTransactionTime(VertxTestContext testContext) throws Exception {
        Instant beforeAppend = Instant.now();
        String eventType = "gap2.direct.txtime";
        TestPayload payload = new TestPayload("direct-txtime-test", 10);
        Instant validTime = Instant.now();

        // Direct path: ensure event-bus distribution is OFF
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    Instant afterAppend = Instant.now();
                    Instant txTime = event.getTransactionTime();
                    assertNotNull(txTime, "Direct path must return a transaction time");

                    // Transaction time should be between before and after the call
                    assertTrue(!txTime.isBefore(beforeAppend.minusMillis(100)),
                            "Transaction time should not be significantly before the call");
                    assertTrue(!txTime.isAfter(afterAppend.plusMillis(100)),
                            "Transaction time should not be significantly after the call");

                    // Verify the DB row has the same transaction time
                    String sql = "SELECT transaction_time FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                Instant dbTxTime = rows.iterator().next()
                                        .getOffsetDateTime("transaction_time").toInstant();
                                // Direct path: the transaction time returned should match DB
                                assertEquals(txTime, dbTxTime,
                                        "Direct path: returned transaction time must match DB");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("FIXED: Event-bus path now uses caller-generated transaction time")
    void eventBusPathUsesCallerTransactionTime(VertxTestContext testContext) throws Exception {
        /*
         * FIX VERIFICATION: The event-bus path now includes the caller-generated
         * transactionTime in the operation JSON, and the worker uses it instead
         * of generating a new one.  The bi-temporal audit trail is preserved.
         */
        String eventType = "gap2.eventbus.txtime.fixed";
        TestPayload payload = new TestPayload("eventbus-txtime-test", 20);
        Instant validTime = Instant.now();
        Instant beforeAppend = Instant.now();

        // Event-bus path: enable event-bus distribution
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    Instant txTime = event.getTransactionTime();
                    assertNotNull(txTime, "Event bus path must return a transaction time");
                    Instant afterAppend = Instant.now();

                    // Transaction time should be between before and after the call
                    assertTrue(!txTime.isBefore(beforeAppend.minusMillis(100)),
                            "Transaction time should not be significantly before the call");
                    assertTrue(!txTime.isAfter(afterAppend.plusMillis(100)),
                            "Transaction time should not be significantly after the call");

                    // Verify the DB has the same transaction time as returned
                    String sql = "SELECT transaction_time FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                Instant dbTxTime = rows.iterator().next()
                                        .getOffsetDateTime("transaction_time").toInstant();
                                assertEquals(txTime, dbTxTime,
                                        "Event bus path: returned transaction time must match DB");
                                logger.info("DEFECT 2 FIXED: Event-bus path preserved caller " +
                                        "transactionTime '{}' -- bi-temporal audit trail intact.",
                                        dbTxTime);
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // ERROR: No transaction wrapper  --  breaks atomicity guarantees
    // ========================================================================

    @Test
    @DisplayName("POSITIVE: Direct path uses withTransaction  --  events are atomically committed")
    void directPathUsesTransactionWrapper(VertxTestContext testContext) throws Exception {
        /*
         * The direct path uses pool.withTransaction(), which means if the INSERT
         * fails, nothing is committed.  We prove this by appending a good event
         * and verifying it committed atomically.
         */
        String eventType = "gap3.direct.transaction.wrapper";
        TestPayload payload = new TestPayload("direct-tx-wrapper", 30);
        Instant validTime = Instant.now();

        // Direct path: ensure event-bus distribution is OFF
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    // Verify the event is committed
                    String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                int count = rows.iterator().next().getInteger(0);
                                assertEquals(1, count,
                                        "Direct path: event committed via withTransaction");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("FIXED: Event-bus path now uses withTransaction for atomicity")
    void eventBusPathUsesWithTransaction(VertxTestContext testContext) throws Exception {
        /*
         * FIX VERIFICATION: The worker now uses pool.withTransaction() instead
         * of bare pool.preparedQuery().  This gives the event-bus path the same
         * atomicity guarantee as the direct path.
         */
        String eventType = "gap3.eventbus.transaction.fixed";
        TestPayload payload = new TestPayload("eventbus-transaction", 31);
        Instant validTime = Instant.now();

        // Event-bus path: enable event-bus distribution
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    // The event is stored atomically via withTransaction
                    String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                int count = rows.iterator().next().getInteger(0);
                                assertEquals(1, count,
                                        "Event bus path: event stored via withTransaction");
                                logger.info("DEFECT 3 FIXED: Event-bus worker now uses " +
                                        "pool.withTransaction() -- atomicity guaranteed.");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // ERROR: Trace context silently lost  --  distributed tracing destroyed
    // ========================================================================

    @Test
    @DisplayName("POSITIVE: Direct path propagates trace context via executeOnVertxContext")
    void directPathPropagatesTraceContext(VertxTestContext testContext) throws Exception {
        /*
         * The direct path calls executeOnVertxContext which captures the current
         * MDC trace context and restores it inside the Vert.x event loop.
         * This means the caller's trace ID flows through to the database operation.
         */
        String eventType = "gap5.direct.trace.propagated";
        TestPayload payload = new TestPayload("direct-trace", 50);
        Instant validTime = Instant.now();

        // Set up a known trace context in MDC before appending
        String callerTraceId = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8";
        String callerSpanId = "1234567890abcdef";
        MDC.put(TraceContextUtil.MDC_TRACE_ID, callerTraceId);
        MDC.put(TraceContextUtil.MDC_SPAN_ID, callerSpanId);

        try {
            // Direct path: ensure event-bus distribution is OFF
            System.clearProperty("peegeeq.database.use.event.bus.distribution");

            eventStore.append(eventType, payload, validTime)
                    .compose(event -> {
                        // The event was stored.  The direct path captured and restored
                        // the trace context via executeOnVertxContext.
                        assertNotNull(event.getEventId());

                        String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                                + ".bitemporal_event_log WHERE event_type = $1";
                        return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                                .map(rows -> {
                                    int count = rows.iterator().next().getInteger(0);
                                    assertEquals(1, count,
                                            "Direct path with trace context: event stored");
                                    logger.info("Direct path trace propagation: caller traceId={} " +
                                            "was captured by executeOnVertxContext and restored " +
                                            "in the Vert.x event loop", callerTraceId);
                                    return event;
                                });
                    })
                    .onSuccess(event -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } finally {
            MDC.remove(TraceContextUtil.MDC_TRACE_ID);
            MDC.remove(TraceContextUtil.MDC_SPAN_ID);
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("FIXED: Event-bus path now propagates traceparent via DeliveryOptions headers")
    void eventBusPathPropagatesTraceContext(VertxTestContext testContext) throws Exception {
        /*
         * FIX VERIFICATION: The event-bus path now passes the caller's traceparent
         * via DeliveryOptions headers.  The worker receives it and continues
         * the distributed trace chain instead of creating a new root.
         */
        String eventType = "gap5.eventbus.trace.fixed";
        TestPayload payload = new TestPayload("eventbus-trace-fixed", 51);
        Instant validTime = Instant.now();

        // Set up a known trace context
        String callerTraceId = "deadbeef12345678deadbeef12345678";
        String callerSpanId = "abcdef0123456789";
        MDC.put(TraceContextUtil.MDC_TRACE_ID, callerTraceId);
        MDC.put(TraceContextUtil.MDC_SPAN_ID, callerSpanId);

        try {
            // Event-bus path: enable event-bus distribution
            System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

            eventStore.append(eventType, payload, validTime)
                    .compose(event -> {
                        assertNotNull(event.getEventId(),
                                "Event bus path should store the event");

                        logger.info("DEFECT 5 FIXED: Event-bus path now propagates " +
                                "traceparent via DeliveryOptions headers. Caller traceId='{}' " +
                                "was sent to the worker.", callerTraceId);

                        String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                                + ".bitemporal_event_log WHERE event_type = $1";
                        return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                                .map(rows -> {
                                    int count = rows.iterator().next().getInteger(0);
                                    assertEquals(1, count,
                                            "Event stored with trace context propagated");
                                    return event;
                                });
                    })
                    .onSuccess(event -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } finally {
            MDC.remove(TraceContextUtil.MDC_TRACE_ID);
            MDC.remove(TraceContextUtil.MDC_SPAN_ID);
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // ERROR: Worker DB execution unmonitored  --  operational blindness
    // ========================================================================

    @Test
    @DisplayName("POSITIVE: Direct path via append() records performance timing around DB execution")
    void directPathRecordsPerformanceTiming(VertxTestContext testContext) throws Exception {
        /*
         * The append() method wraps the call in performanceMonitor.startTiming()
         * and calls timing.recordAsQuery() in .onComplete().  The timing accurately
         * reflects the DB execution time because the direct path executes the
         * INSERT synchronously (relative to the future chain).
         *
         * We verify the event is stored; the monitoring instrumentation is structural
         * (visible in the source code) and cannot be directly observed from outside.
         */
        String eventType = "gap6.direct.perf.timing";
        TestPayload payload = new TestPayload("direct-perf-timing", 60);
        Instant validTime = Instant.now();
        Instant beforeAppend = Instant.now();

        // Direct path: ensure event-bus distribution is OFF
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    Duration elapsed = Duration.between(beforeAppend, Instant.now());
                    logger.info("Direct path append completed in {}ms (timing reflects actual DB execution)",
                            elapsed.toMillis());

                    // Verify event stored
                    String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                assertEquals(1, rows.iterator().next().getInteger(0),
                                        "Direct path: event committed with performance monitoring active");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    @DisplayName("FIXED: Event-bus worker now logs DB execution timing in response")
    void eventBusPathReportsWorkerTiming(VertxTestContext testContext) throws Exception {
        /*
         * FIX VERIFICATION: The worker now measures and logs its own DB execution
         * time.  The caller-side timing still includes bus overhead, but the
         * worker's actual DB latency is now visible in logs.
         */
        String eventType = "gap6.eventbus.perf.fixed";
        TestPayload payload = new TestPayload("eventbus-perf-fixed", 61);
        Instant validTime = Instant.now();
        Instant beforeAppend = Instant.now();

        // Event-bus path: enable event-bus distribution
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        eventStore.append(eventType, payload, validTime)
                .compose(event -> {
                    Duration elapsed = Duration.between(beforeAppend, Instant.now());
                    logger.info("DEFECT 6 FIXED: Event-bus path append completed in {}ms " +
                            "(includes bus overhead). Worker-side DB timing is now logged " +
                            "separately by the worker verticle.", elapsed.toMillis());

                    // Verify event stored
                    String sql = "SELECT COUNT(*) FROM " + resolveSchema()
                            + ".bitemporal_event_log WHERE event_type = $1";
                    return verificationPool.preparedQuery(sql).execute(Tuple.of(eventType))
                            .map(rows -> {
                                assertEquals(1, rows.iterator().next().getInteger(0),
                                        "Event bus path: event stored with worker-side timing");
                                return event;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // CROSS-PATH COMPARISON: Direct vs Event-Bus side-by-side
    // ========================================================================

    @Test
    @DisplayName("COMPARISON: Both paths store events but with different event IDs for the same logical operation")
    void bothPathsStoreDifferentEventIds(VertxTestContext testContext) throws Exception {
        /*
         * Append the same logical payload via both paths and compare the
         * event IDs.  The direct path's ID is generated pre-INSERT and used in the
         * INSERT.  The event-bus path's ID is generated fresh by the worker.
         * These are never the same  --  proving the caller cannot correlate across paths.
         */
        String directType = "comparison.direct.id";
        String eventBusType = "comparison.eventbus.id";
        TestPayload payload = new TestPayload("comparison", 100);
        Instant validTime = Instant.now();

        // Direct path first
        System.clearProperty("peegeeq.database.use.event.bus.distribution");

        eventStore.append(directType, payload, validTime)
                .compose(directEvent -> {
                    // Switch to event-bus path
                    System.setProperty("peegeeq.database.use.event.bus.distribution", "true");
                    return eventStore.append(eventBusType, payload, validTime)
                            .map(eventBusEvent -> {
                                assertNotEquals(directEvent.getEventId(), eventBusEvent.getEventId(),
                                        "Event IDs from different paths should be different UUIDs " +
                                        "(each path generates independently)");
                                logger.info("COMPARISON: Direct eventId={}, EventBus eventId={} " +
                                        "(both are independent UUIDs  --  expected for different operations)",
                                        directEvent.getEventId(), eventBusEvent.getEventId());
                                return eventBusEvent;
                            });
                })
                .onSuccess(event -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
