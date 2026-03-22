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
 * <p>These tests demonstrate three concrete facts:
 * <ol>
 *   <li>append() and appendWithTransaction() start their own independent
 *       transaction on the internal pool — the event survives an external
 *       rollback because it never participates in the external transaction.</li>
 *   <li>append() and appendWithTransaction() are semantically equivalent —
 *       both start a fresh own-transaction every time.</li>
 *   <li>Only appendInTransaction(connection) genuinely participates in an
 *       external transaction — rollback on the connection rolls back the event.</li>
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
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
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
        if (eventStore != null) {
            eventStore.close();
        }

        Future<Void> closeFuture = Future.succeededFuture();
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
    // Test 1: appendWithTransaction starts own-tx, does NOT join external
    // ========================================================================

    @Test
    @DisplayName("appendWithTransaction starts own-transaction — event survives external rollback")
    void appendWithTransactionDoesNotJoinExternalTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Scenario:
         *   1. Start a transaction on the EXTERNAL pool (different Vertx instance).
         *   2. Inside that transaction, call appendWithTransaction().
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
            return eventStore.appendWithTransaction(eventType, payload, validTime,
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
            // appendInTransaction(connection) — see tests 3 and 5.
            assertEquals(1, count,
                    "Event should survive external rollback because " +
                    "appendWithTransaction() starts an own-transaction on " +
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
    // Test 2: append() and appendWithTransaction() are both own-tx
    // ========================================================================

    @Test
    @DisplayName("append() and appendWithTransaction() both start own-transaction — semantically equivalent")
    void appendAndAppendWithTransactionAreBothOwnTransaction(VertxTestContext testContext) throws Exception {
        /*
         * Scenario:
         *   1. Call append() — starts its own transaction.
         *   2. Call appendWithTransaction() — also starts its own transaction.
         *   3. Both should succeed independently — proving they are
         *      semantically equivalent (both start a fresh own-transaction).
         *   4. Both events should be independently committed.
         */
        String schema = resolveSchema();
        Instant validTime = Instant.now();
        String appendEventType = "via.append.own.tx";
        String withTxEventType = "via.appendWithTransaction.own.tx";

        eventStore.append(appendEventType, new TestPayload("via-append", 10), validTime)
                .compose(appendEvent -> {
                    return eventStore.appendWithTransaction(
                            withTxEventType,
                            new TestPayload("via-appendWithTransaction", 20),
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
                            "append() and appendWithTransaction() both start own-transactions");
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
    // Test 4: Injected Vertx is the manager's Vertx (no hidden creation)
    // ========================================================================

    @Test
    @DisplayName("Event store uses the injected Vertx from PeeGeeQManager — no hidden Vertx creation")
    void injectedVertxIsManagerVertx(VertxTestContext testContext) throws Exception {
        /*
         * After the injection refactoring, the event store's Vertx should be
         * the same instance provided by PeeGeeQManager — proving no hidden
         * resource creation.
         */
        Vertx storeVertx = PgBiTemporalEventStore.getOrCreateSharedVertx();
        Vertx managerVertx = manager.getVertx();

        testContext.verify(() -> {
            assertNotNull(storeVertx, "Event store Vertx should exist");
            assertNotNull(managerVertx, "Manager Vertx should exist");
            assertSame(managerVertx, storeVertx,
                    "The event store's Vertx must be the same instance as the manager's Vertx — " +
                    "proving injection works and no hidden Vertx is created");
        });
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========================================================================
    // Test 5: Contrast — appendInTransaction commits when transaction commits
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
}
