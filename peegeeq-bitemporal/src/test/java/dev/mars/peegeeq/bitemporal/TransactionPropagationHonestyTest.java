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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying the honest transaction participation contract in
 * PgBiTemporalEventStore.
 *
 * <p>These tests demonstrate the following facts:
 * <ol>
 *   <li>append() and appendOwnTransaction() start their own independent
 *       transaction on the internal pool — the event survives an external
 *       rollback because it never participates in the external transaction.</li>
 *   <li>append() and appendOwnTransaction() are semantically equivalent —
 *       both start a fresh own-transaction every time.</li>
 *   <li>Only appendInTransaction(connection) genuinely participates in an
 *       external transaction — rollback on the connection rolls back the event.</li>
 *   <li>appendOwnTransaction() persists all metadata fields (headers,
 *       correlationId, causationId, aggregateId) via the own-transaction path.</li>
 *   <li>appendInTransaction(null) returns a failed future with
 *       IllegalArgumentException, not NPE.</li>
 *   <li>A closed event store rejects append() and appendInTransaction()
 *       with IllegalStateException.</li>
 *   <li>The builder's validate() rejects missing required fields synchronously.</li>
 *   <li>The builder's correction() path takes priority over inTransaction() —
 *       when both are set, the connection is silently ignored.</li>
 * </ol>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class TransactionPropagationHonestyTest {

    private static final Logger logger = LoggerFactory.getLogger(TransactionPropagationHonestyTest.class);
    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("txprop_honesty_" + System.currentTimeMillis());
        container.withUsername("peegeeq");
        container.withPassword("peegeeq_test_password");
        return container;
    }

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestPayload> eventStore;
    private Vertx vertx;

    // An external Vertx instance that is NOT the event store's internal sharedVertx.
    // This simulates the "caller started work on some other Vert.x instance" scenario.
    private Vertx externalVertx;
    private Pool externalPool;

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

        // Create an external Vertx instance — deliberately separate from the event
        // store's internal sharedVertx.  This is the caller's world.
        externalVertx = Vertx.vertx();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Create external pool on the external Vertx
        externalPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(connectOptions())
                .using(externalVertx)
                .build();

        Pool setupPool = PgBuilder.pool().connectingTo(connectOptions()).using(vertx).build();

        setupPool.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
                .recover(err -> Future.succeededFuture(null))
                .compose(v -> setupPool.close())
                .compose(v -> manager.start())
                .onSuccess(v -> {
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(vertx, manager);
                    eventStore = (PgBiTemporalEventStore<TestPayload>) factory.createEventStore(
                            TestPayload.class, "bitemporal_event_log");
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
        Future<Void> closeFuture = Future.succeededFuture();
        if (eventStore != null) {
            closeFuture = closeFuture.compose(v -> eventStore.close());
        }
        if (externalPool != null) {
            closeFuture = closeFuture.compose(v -> externalPool.close());
        }
        if (manager != null) {
            closeFuture = closeFuture.compose(v -> manager.closeReactive());
        }
        if (externalVertx != null) {
            closeFuture = closeFuture.compose(v -> externalVertx.close());
        }
        if (vertx != null) {
            closeFuture = closeFuture.compose(v -> vertx.close());
        }

        closeFuture
                .onSuccess(v -> {
                    clearTestSystemProperties();
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    clearTestSystemProperties();
                    testContext.failNow(err);
                });

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    private void clearTestSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.health-check.enabled");
        System.clearProperty("peegeeq.health-check.queue-checks-enabled");
        System.clearProperty("peegeeq.queue.dead-consumer-detection.enabled");
        System.clearProperty("peegeeq.queue.recovery.enabled");
    }

    // ========================================================================
    // Test 1: appendOwnTransaction starts own-tx, does NOT join external
    // ========================================================================

    @Test
    @DisplayName("appendOwnTransaction starts own-transaction — event survives external rollback")
    void appendOwnTransactionDoesNotJoinExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Scenario:
         *   1. Start a transaction on the EXTERNAL pool (different Vertx instance).
         *   2. Inside that transaction, call appendOwnTransaction().
         *      The event store always starts its own independent transaction
         *      on its internal pool.
         *   3. Deliberately roll back the external transaction.
         *   4. Query the event store — the event should still be there because
         *      the event store started its own independent transaction.
         */
        String schema = resolveSchema();
        String eventType = "cross.vertx.own.tx.test";
        TestPayload payload = new TestPayload("should-survive-external-rollback", 1);
        Instant validTime = Instant.now();

        externalPool.withTransaction(externalConn -> {
            // We're on the external Vertx context now.
            // Call the event store's own-transaction method.
            return eventStore.appendOwnTransaction(eventType, payload, validTime,
                            Map.of(), null, null, null)
                    .compose(event -> {
                        // Got the event back — now deliberately fail to trigger rollback.
                        return Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                new RuntimeException("Deliberate rollback"));
                    });
        })
        // The external transaction rolled back.
        .recover(err -> {
            logger.info("External transaction rolled back as expected: {}", err.getMessage());
            return Future.succeededFuture(null);
        })
        .compose(ignored -> {
            // Now query the event log.  The event store started its own
            // transaction, so the event should survive the external rollback.
            String countSql = "SELECT COUNT(*) FROM " + schema + ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);

            // CORRECT BEHAVIOUR: the event store starts its own independent
            // transaction on its internal pool, so the event survives the
            // external rollback.
            //
            // For genuine transaction participation, callers must use
            // appendInTransaction(connection) — see tests 3 and 4.
            assertEquals(1, count,
                    "Event should survive external rollback because " +
                    "appendOwnTransaction() starts an own-transaction on " +
                    "the internal pool — it does not join external transactions");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 2: append() and appendOwnTransaction() are both own-tx
    // ========================================================================

    @Test
    @DisplayName("append() and appendOwnTransaction() both start own-transaction — semantically equivalent")
    void appendAndAppendOwnTransactionAreBothOwnTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Scenario:
         *   1. Call append() — starts its own transaction.
         *   2. Call appendOwnTransaction() — also starts its own transaction.
         *   3. Both should succeed independently — proving they are
         *      semantically equivalent (both start a fresh own-transaction).
         *   4. Both events should be independently committed.
         */
        String schema = resolveSchema();
        Instant validTime = Instant.now();
        String appendEventType = "via.append.own.tx";
        String withTxEventType = "via.appendOwnTransaction.own.tx";

        eventStore.append(appendEventType, new TestPayload("via-append", 10), validTime)
                .compose(appendEvent -> {
                    return eventStore.appendOwnTransaction(
                            withTxEventType,
                            new TestPayload("via-appendOwnTransaction", 20),
                            validTime,
                            Map.of(),
                            null, null, null
                    ).map(ownEvent -> appendEvent);
                })
                .compose(appendEvent -> {
                    // Count both events
                    String countSql = "SELECT event_type, COUNT(*) as cnt FROM " + schema +
                            ".bitemporal_event_log WHERE event_type IN ($1, $2) GROUP BY event_type";
                    return externalPool.preparedQuery(countSql)
                            .execute(Tuple.of(appendEventType, withTxEventType));
                })
                .onSuccess(rows -> testContext.verify(() -> {
                    int typesFound = 0;
                    for (var row : rows) {
                        typesFound++;
                        assertEquals(1, row.getInteger("cnt"),
                                "Each event type should have exactly one committed event");
                    }
                    assertEquals(2, typesFound,
                            "Both event types should be independently committed — " +
                            "append() and appendOwnTransaction() both start own-transactions");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 3: appendInTransaction(connection) DOES participate — control test
    // ========================================================================

    @Test
    @DisplayName("appendInTransaction(connection) genuinely participates — rollback rolls back the event")
    void appendInTransactionGenuinelyParticipates(VertxTestContext testContext) throws Exception {
        /*
         * Control test proving that appendInTransaction(connection) is the real
         * transactional API:
         *   1. Start a transaction on an external pool.
         *   2. Call appendInTransaction(connection) on the same connection.
         *   3. Roll back the transaction.
         *   4. The event should be gone — genuine participation.
         */
        String schema = resolveSchema();
        String eventType = "genuine.participation.rollback";
        TestPayload payload = new TestPayload("should-be-rolled-back", 99);
        Instant validTime = Instant.now();

        externalPool.withTransaction(conn ->
                eventStore.appendInTransaction(eventType, payload, validTime, conn)
                        .compose(event -> {
                            logger.info("Event appended via appendInTransaction: {}", event.getEventId());
                            // Deliberately fail to trigger rollback
                            return Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                    new RuntimeException("Deliberate rollback"));
                        })
        )
        .recover(err -> {
            logger.info("Transaction rolled back as expected: {}", err.getMessage());
            return Future.succeededFuture(null);
        })
        .compose(ignored -> {
            String countSql = "SELECT COUNT(*) FROM " + schema + ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(0, count,
                    "Event MUST be rolled back when the connection's transaction is rolled back — " +
                    "appendInTransaction genuinely participates in the caller's transaction");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 4: Contrast — appendInTransaction commits when transaction commits
    // ========================================================================

    @Test
    @DisplayName("appendInTransaction(connection) commits with the external transaction — positive control")
    void appendInTransactionCommitsWithExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Positive control for Test 3:
         *   1. Start a transaction on external pool.
         *   2. appendInTransaction(connection) on same connection.
         *   3. Let the transaction commit normally.
         *   4. Event should be visible.
         */
        String schema = resolveSchema();
        String eventType = "genuine.participation.commit";
        TestPayload payload = new TestPayload("should-commit-with-tx", 77);
        Instant validTime = Instant.now();

        externalPool.withTransaction(conn ->
                eventStore.appendInTransaction(eventType, payload, validTime, conn)
        )
        .compose(event -> {
            logger.info("Transaction committed with event: {}", event.getEventId());
            String countSql = "SELECT COUNT(*) FROM " + schema + ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(1, count,
                    "Event should be visible after the external transaction commits — " +
                    "appendInTransaction genuinely participates");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 5: append() also starts own-tx — event survives external rollback
    // ========================================================================

    @Test
    @DisplayName("append() starts own-transaction — event survives external rollback")
    void appendDoesNotJoinExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Same scenario as Test 1 but using append() directly.
         * Proves that append() — not just appendOwnTransaction() — starts its
         * own independent transaction and ignores any external transaction context.
         */
        String schema = resolveSchema();
        String eventType = "append.own.tx.survives.rollback";
        TestPayload payload = new TestPayload("append-survives-rollback", 60);
        Instant validTime = Instant.now();

        externalPool.withTransaction(externalConn ->
                eventStore.append(eventType, payload, validTime)
                        .compose(event ->
                                Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                        new RuntimeException("Deliberate rollback")))
        )
        .recover(err -> {
            logger.info("External transaction rolled back as expected: {}", err.getMessage());
            return Future.succeededFuture(null);
        })
        .compose(ignored -> {
            String countSql = "SELECT COUNT(*) FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(1, count,
                    "Event should survive external rollback because append() starts " +
                    "its own independent transaction — it never participates in " +
                    "the caller's transaction");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 6: appendBuilder().execute() starts own-tx — survives rollback
    // ========================================================================

    @Test
    @DisplayName("appendBuilder().execute() starts own-transaction — event survives external rollback")
    void builderWithoutInTransactionDoesNotJoinExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * The builder's execute() without inTransaction(conn) delegates to
         * append() — which starts its own independent transaction.
         * Proves the builder path also does NOT participate.
         */
        String schema = resolveSchema();
        String eventType = "builder.own.tx.survives.rollback";
        TestPayload payload = new TestPayload("builder-survives-rollback", 70);
        Instant validTime = Instant.now();

        externalPool.withTransaction(externalConn ->
                eventStore.appendBuilder()
                        .eventType(eventType)
                        .payload(payload)
                        .validTime(validTime)
                        .header("source", "builder-test")
                        .correlationId("corr-builder-70")
                        .execute()
                        .compose(event ->
                                Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                        new RuntimeException("Deliberate rollback")))
        )
        .recover(err -> Future.succeededFuture(null))
        .compose(ignored -> {
            String countSql = "SELECT COUNT(*) FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(1, count,
                    "Builder execute() without inTransaction() starts its own " +
                    "transaction — event survives external rollback");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 7: appendBuilder().inTransaction(conn).execute() — rollback
    // ========================================================================

    @Test
    @DisplayName("appendBuilder().inTransaction(conn).execute() genuinely participates — rollback rolls back event")
    void builderInTransactionRollsBackWithExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * The builder's inTransaction(conn) path delegates to
         * appendInTransaction(eventType, payload, validTime, headers,
         *     correlationId, causationId, aggregateId, connection)
         * — which uses the caller's connection.  Rollback must remove the event.
         */
        String schema = resolveSchema();
        String eventType = "builder.in.tx.rollback";
        TestPayload payload = new TestPayload("builder-rolled-back", 80);
        Instant validTime = Instant.now();

        externalPool.withTransaction(conn ->
                eventStore.appendBuilder()
                        .eventType(eventType)
                        .payload(payload)
                        .validTime(validTime)
                        .header("source", "builder-in-tx")
                        .correlationId("corr-builder-80")
                        .causationId("cause-builder-80")
                        .aggregateId("agg-builder-80")
                        .inTransaction(conn)
                        .execute()
                        .compose(event ->
                                Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                        new RuntimeException("Deliberate rollback")))
        )
        .recover(err -> Future.succeededFuture(null))
        .compose(ignored -> {
            String countSql = "SELECT COUNT(*) FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(0, count,
                    "Builder inTransaction(conn) MUST participate — rollback " +
                    "removes the event");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 8: appendBuilder().inTransaction(conn).execute() — commit
    // ========================================================================

    @Test
    @DisplayName("appendBuilder().inTransaction(conn).execute() genuinely participates — commit persists event")
    void builderInTransactionCommitsWithExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Positive control for Test 7: the builder's inTransaction(conn) path
         * commits the event when the external transaction commits.
         */
        String schema = resolveSchema();
        String eventType = "builder.in.tx.commit";
        TestPayload payload = new TestPayload("builder-committed", 90);
        Instant validTime = Instant.now();

        externalPool.withTransaction(conn ->
                eventStore.appendBuilder()
                        .eventType(eventType)
                        .payload(payload)
                        .validTime(validTime)
                        .header("source", "builder-in-tx-commit")
                        .correlationId("corr-builder-90")
                        .inTransaction(conn)
                        .execute()
        )
        .compose(event -> {
            String sql = "SELECT correlation_id, headers::text FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(sql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            var iter = rows.iterator();
            assertTrue(iter.hasNext(), "Event must exist after commit");
            var row = iter.next();
            assertEquals("corr-builder-90", row.getString("correlation_id"),
                    "Correlation ID must be persisted via builder inTransaction path");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 9: appendInTransaction full metadata — commit persists all fields
    // ========================================================================

    @Test
    @DisplayName("appendInTransaction with full metadata commits all fields including causationId and aggregateId")
    void appendInTransactionFullMetadataCommit(VertxTestContext testContext) throws Exception {
        /*
         * Tests the full 8-arg appendInTransaction overload with headers,
         * correlationId, causationId, and aggregateId.  Verifies all metadata
         * fields are persisted via the genuine transaction participation path.
         */
        String schema = resolveSchema();
        String eventType = "full.metadata.in.tx.commit";
        TestPayload payload = new TestPayload("full-metadata", 100);
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("env", "test", "version", "2");
        String correlationId = "corr-full-100";
        String causationId = "cause-full-100";
        String aggregateId = "agg-full-100";

        externalPool.withTransaction(conn ->
                eventStore.appendInTransaction(eventType, payload, validTime,
                        headers, correlationId, causationId, aggregateId, conn)
        )
        .compose(event -> {
            // Verify the returned event has all metadata
            testContext.verify(() -> {
                assertNotNull(event.getEventId(), "Event ID must be generated");
                assertEquals(eventType, event.getEventType());
                assertEquals(correlationId, event.getCorrelationId());
                assertEquals(causationId, event.getCausationId());
                assertEquals(aggregateId, event.getAggregateId());
                assertEquals("test", event.getHeaders().get("env"));
                assertEquals("2", event.getHeaders().get("version"));
            });

            // Also verify from the database directly
            String sql = "SELECT correlation_id, causation_id, aggregate_id, headers::text " +
                    "FROM " + schema + ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(sql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            var iter = rows.iterator();
            assertTrue(iter.hasNext(), "Event must be persisted");
            var row = iter.next();
            assertEquals(correlationId, row.getString("correlation_id"));
            assertEquals(causationId, row.getString("causation_id"));
            assertEquals(aggregateId, row.getString("aggregate_id"));
            String headersJson = row.getString("headers");
            assertTrue(headersJson.contains("\"env\""), "Headers must be persisted");
            assertTrue(headersJson.contains("\"version\""), "Headers must be persisted");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 10: appendInTransaction full metadata — rollback removes everything
    // ========================================================================

    @Test
    @DisplayName("appendInTransaction with full metadata rolls back — nothing persisted including metadata")
    void appendInTransactionFullMetadataRollback(VertxTestContext testContext) throws Exception {
        /*
         * Counterpart to Test 9: verifies that when the transaction is rolled
         * back, no trace of the event (including metadata) remains.
         */
        String schema = resolveSchema();
        String eventType = "full.metadata.in.tx.rollback";
        TestPayload payload = new TestPayload("full-metadata-gone", 101);
        Instant validTime = Instant.now();

        externalPool.withTransaction(conn ->
                eventStore.appendInTransaction(eventType, payload, validTime,
                        Map.of("env", "test"), "corr-101", "cause-101", "agg-101", conn)
                        .compose(event ->
                                Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                        new RuntimeException("Deliberate rollback")))
        )
        .recover(err -> Future.succeededFuture(null))
        .compose(ignored -> {
            String countSql = "SELECT COUNT(*) FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(0, count,
                    "Full-metadata appendInTransaction must leave no trace after rollback");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 11: appendOwnTransaction full metadata — commit persists all fields
    // ========================================================================

    @Test
    @DisplayName("appendOwnTransaction with full metadata commits all fields including causationId and aggregateId")
    void appendOwnTransactionFullMetadataCommit(VertxTestContext testContext) throws Exception {
        /*
         * Tests the full 7-arg appendOwnTransaction overload with headers,
         * correlationId, causationId, and aggregateId.  Verifies all metadata
         * fields are persisted via the own-transaction path.
         */
        String schema = resolveSchema();
        String eventType = "full.metadata.own.tx.commit";
        TestPayload payload = new TestPayload("own-tx-full-metadata", 110);
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("env", "test", "source", "honesty-test");
        String correlationId = "corr-own-110";
        String causationId = "cause-own-110";
        String aggregateId = "agg-own-110";

        eventStore.appendOwnTransaction(eventType, payload, validTime,
                headers, correlationId, causationId, aggregateId)
        .compose(event -> {
            // Verify the returned event has all metadata
            testContext.verify(() -> {
                assertNotNull(event.getEventId(), "Event ID must be generated");
                assertEquals(eventType, event.getEventType());
                assertEquals(correlationId, event.getCorrelationId());
                assertEquals(causationId, event.getCausationId());
                assertEquals(aggregateId, event.getAggregateId());
                assertEquals("test", event.getHeaders().get("env"));
                assertEquals("honesty-test", event.getHeaders().get("source"));
            });

            // Also verify from the database directly
            String sql = "SELECT correlation_id, causation_id, aggregate_id, headers::text " +
                    "FROM " + schema + ".bitemporal_event_log WHERE event_type = $1";
            return externalPool.preparedQuery(sql).execute(Tuple.of(eventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            var iter = rows.iterator();
            assertTrue(iter.hasNext(), "Event must be persisted");
            var row = iter.next();
            assertEquals(correlationId, row.getString("correlation_id"));
            assertEquals(causationId, row.getString("causation_id"));
            assertEquals(aggregateId, row.getString("aggregate_id"));
            String headersJson = row.getString("headers");
            assertTrue(headersJson.contains("\"env\""), "Headers key 'env' must be persisted");
            assertTrue(headersJson.contains("\"source\""), "Headers key 'source' must be persisted");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 12: appendInTransaction with null connection — returns failed future
    // ========================================================================

    @Test
    @DisplayName("appendInTransaction with null connection returns failed future with IllegalArgumentException")
    void appendInTransactionNullConnectionReturnsFailed(VertxTestContext testContext) throws Exception {
        /*
         * Boundary test: calling appendInTransaction with a null connection
         * must return a failed future with IllegalArgumentException, not NPE.
         */
        String eventType = "null.connection.test";
        TestPayload payload = new TestPayload("null-conn", 120);
        Instant validTime = Instant.now();

        eventStore.appendInTransaction(eventType, payload, validTime, null)
        .onSuccess(event -> testContext.failNow(
                new AssertionError("Should have failed with IllegalArgumentException")))
        .onFailure(err -> testContext.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err,
                    "Null connection must produce IllegalArgumentException");
            assertTrue(err.getMessage().contains("connection cannot be null"),
                    "Error message must mention null connection");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 13: Closed event store rejects append with failed future
    // ========================================================================

    @Test
    @DisplayName("Closed event store rejects append() and appendInTransaction() with IllegalStateException")
    void closedStoreRejectsAppend(VertxTestContext testContext) throws Exception {
        /*
         * After close(), all append methods must return a failed future
         * with IllegalStateException("Event store is closed").
         *
         * We test both code paths:
         *   - append() → appendOwnTransaction() → appendOwnTransactionInternal() closed check
         *   - appendInTransaction(8-arg) has its own independent closed check
         */
        String eventType = "closed.store.test";
        TestPayload payload = new TestPayload("closed-store", 130);
        Instant validTime = Instant.now();

        // Close the event store first, then null the field so tearDown
        // does not attempt a redundant second close.
        PgBiTemporalEventStore<TestPayload> closedStore = eventStore;
        eventStore = null;

        closedStore.close()
        // Verify append() is rejected
        .compose(v -> closedStore.append(eventType, payload, validTime)
                .compose(event -> Future.<BiTemporalEvent<TestPayload>>failedFuture(
                        new AssertionError("append() should have been rejected by closed store"))))
        .recover(err -> {
            testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err,
                        "Closed store must produce IllegalStateException from append()");
                assertTrue(err.getMessage().contains("Event store is closed"),
                        "Error message must mention closed store");
            });
            return Future.succeededFuture(null);
        })
        // Verify the independent closed check in appendInTransaction(8-arg).
        // Pass null connection — the closed check fires first (before null-connection check).
        .compose(v -> closedStore.appendInTransaction(eventType, payload, validTime,
                Map.of(), null, null, null, null)
                .compose(event -> Future.<BiTemporalEvent<TestPayload>>failedFuture(
                        new AssertionError("appendInTransaction() should have been rejected by closed store"))))
        .recover(err -> {
            testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err,
                        "Closed store must produce IllegalStateException from appendInTransaction()");
                assertTrue(err.getMessage().contains("Event store is closed"),
                        "Error message must mention closed store");
            });
            return Future.succeededFuture(null);
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 14: Builder validation — missing required fields
    // ========================================================================

    @Test
    @DisplayName("Builder execute() returns failed Future for missing required fields")
    void builderValidationRejectsMissingFields(VertxTestContext testContext) throws Exception {
        /*
         * The builder's validate() throws IllegalStateException synchronously,
         * but execute() catches it and returns Future.failedFuture().
         * Verify each missing-field case produces a failed Future with the right message.
         */
        testContext.verify(() -> {
            // Missing eventType
            Future<?> f1 = eventStore.appendBuilder()
                    .payload(new TestPayload("no-type", 1))
                    .validTime(Instant.now())
                    .execute();
            assertTrue(f1.failed(), "Should fail for missing eventType");
            assertInstanceOf(IllegalStateException.class, f1.cause());
            assertTrue(f1.cause().getMessage().contains("eventType"),
                    "Must mention missing eventType");

            // Missing payload
            Future<?> f2 = eventStore.appendBuilder()
                    .eventType("test.type")
                    .validTime(Instant.now())
                    .execute();
            assertTrue(f2.failed(), "Should fail for missing payload");
            assertInstanceOf(IllegalStateException.class, f2.cause());
            assertTrue(f2.cause().getMessage().contains("payload"),
                    "Must mention missing payload");

            // Missing validTime
            Future<?> f3 = eventStore.appendBuilder()
                    .eventType("test.type")
                    .payload(new TestPayload("no-time", 2))
                    .execute();
            assertTrue(f3.failed(), "Should fail for missing validTime");
            assertInstanceOf(IllegalStateException.class, f3.cause());
            assertTrue(f3.cause().getMessage().contains("validTime"),
                    "Must mention missing validTime");
        });
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 15: Builder correction() + inTransaction() — correction wins,
    //          connection silently ignored
    // ========================================================================

    @Test
    @DisplayName("Builder correction() + inTransaction() — correction path wins, connection is silently ignored")
    void builderCorrectionIgnoresInTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Design-level documentation test: when both correction(id, reason)
         * and inTransaction(conn) are set on the builder, the execute()
         * method routes to the correction path FIRST (checks originalEventId
         * != null before checking connection != null).  The correction path
         * uses appendCorrection() which starts its own transaction via
         * Pool.withTransaction() — the connection is silently ignored.
         *
         * We prove this by:
         *   1. Appending an original event.
         *   2. Starting an external transaction.
         *   3. Using builder with .correction(id, reason).inTransaction(conn).execute()
         *      inside that external transaction.
         *   4. Rolling back the external transaction.
         *   5. Verifying the correction event SURVIVES — because the correction
         *      path used its own transaction, not the caller's connection.
         */
        String schema = resolveSchema();
        String originalEventType = "correction.ignore.original";
        String correctionEventType = "correction.ignore.corrected";
        TestPayload originalPayload = new TestPayload("original", 140);
        TestPayload correctedPayload = new TestPayload("corrected", 141);
        Instant validTime = Instant.now();
        Instant correctedValidTime = validTime.plusSeconds(60);

        // Step 1: Append the original event
        eventStore.append(originalEventType, originalPayload, validTime)
        .compose(originalEvent -> {
            String originalEventId = originalEvent.getEventId();
            logger.info("Original event appended: {}", originalEventId);

            // Step 2-4: Start external tx, use builder with correction + inTransaction, rollback
            return externalPool.withTransaction(conn ->
                    eventStore.appendBuilder()
                            .eventType(correctionEventType)
                            .payload(correctedPayload)
                            .validTime(correctedValidTime)
                            .correction(originalEventId, "Test correction reason")
                            .inTransaction(conn)
                            .execute()
                            .compose(correctionEvent -> {
                                logger.info("Correction event appended: {}", correctionEvent.getEventId());
                                // Deliberately fail to trigger rollback of external tx
                                return Future.<BiTemporalEvent<TestPayload>>failedFuture(
                                        new RuntimeException("Deliberate rollback"));
                            })
            )
            .recover(err -> {
                logger.info("External transaction rolled back as expected: {}", err.getMessage());
                return Future.succeededFuture(null);
            });
        })
        .compose(ignored -> {
            // Step 5: Query for correction events — they should SURVIVE
            // because correction path ignores the connection
            String countSql = "SELECT COUNT(*) FROM " + schema +
                    ".bitemporal_event_log WHERE event_type = $1 AND is_correction = true";
            return externalPool.preparedQuery(countSql).execute(Tuple.of(correctionEventType));
        })
        .onSuccess(rows -> testContext.verify(() -> {
            int count = rows.iterator().next().getInteger(0);
            assertEquals(1, count,
                    "Correction event must survive external rollback — proving the " +
                    "builder routes to appendCorrection() (own-transaction) when " +
                    "correction() is set, silently ignoring inTransaction(conn)");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
