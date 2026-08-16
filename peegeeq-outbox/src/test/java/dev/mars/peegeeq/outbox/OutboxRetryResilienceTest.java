package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Properties;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive test suite for database failure resilience in outbox consumer retry mechanism.
 * 
 * This test suite covers critical database failure scenarios that could occur in production:
 * - Connection timeouts during retry processing
 * - Database server unavailability during retry cycles
 * - Transaction rollback scenarios
 * - Connection pool exhaustion
 * - Database recovery after temporary failures
 * 
 * These tests are essential for ensuring the outbox consumer can handle database failures
 * gracefully without losing messages or corrupting retry state.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxRetryResilienceTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryResilienceTest.class);
    private static final int APPLICATION_POOL_SIZE = 5;

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private QueueFactory queueFactory;
    private PgConnectionManager verificationConnectionManager;
    private Pool verificationPool;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Set up database connection properties
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", Integer.toString(APPLICATION_POOL_SIZE))
                .property("peegeeq.database.pool.max-wait-queue-size", "0")
                .property("peegeeq.database.pool.connection-timeout-ms", "2000")
                .property("peegeeq.database.pool.shared", "false")
                .property("peegeeq.health-check.queue-checks-enabled", "false")
                .build();

        // Initialize manager and components
        meterRegistry = new SimpleMeterRegistry();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), meterRegistry);
        manager.start()
            .map(v -> {
                // Create queue factory using the standard pattern
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith(provider);
                queueFactory = provider.createFactory("outbox", databaseService);

                verificationConnectionManager = new PgConnectionManager(manager.getVertx());
                PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                        .host(postgres.getHost())
                        .port(postgres.getFirstMappedPort())
                        .database(postgres.getDatabaseName())
                        .username(postgres.getUsername())
                        .password(postgres.getPassword())
                        .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                        .build();
                PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                        .maxSize(2)
                        .shared(false)
                        .build();
                verificationPool = verificationConnectionManager.getOrCreateReactivePool(
                        "retry-resilience-verification", connectionConfig, poolConfig);
                return (Void) null;
            })
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        (queueFactory != null ? queueFactory.close() : Future.<Void>succeededFuture())
            .eventually(() -> verificationConnectionManager != null
                    ? verificationConnectionManager.close()
                    : Future.<Void>succeededFuture())
            .eventually(() -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Handler-reported connection timeout reaches dead letter")
    void testConnectionTimeoutDuringRetryProcessing(Vertx vertx, VertxTestContext testContext) {
        assertDeadLetterScenario(
                vertx,
                testContext,
                "connection-timeout",
                "Connection timeout during processing");
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Handler-reported database unavailability reaches dead letter")
    void testDatabaseUnavailabilityDuringRetryProcessing(Vertx vertx, VertxTestContext testContext) {
        assertDeadLetterScenario(
                vertx,
                testContext,
                "database-unavailable",
                "Database connection failed");
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Consumer recovers after the live application pool is exhausted")
    void testConnectionPoolExhaustionDuringRetryProcessing(Vertx vertx, VertxTestContext testContext) {
        String testTopic = newTopic("pool-exhaustion");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();

        producer.send("Message held during live pool exhaustion")
            .compose(v -> manager.getDatabaseService().getConnectionProvider()
                    .getReactivePool("peegeeq-main"))
            .compose(pool -> exhaustPoolAndAssertConsumerRecovery(
                    vertx, testTopic, pool, attemptCount))
            .onSuccess(row -> testContext.verify(() -> {
                assertPersistedState(row, attemptCount, 1, "COMPLETED", 0);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Stuck recovery repairs a message after persistence repair exhausts its retries")
    void testStuckRecoveryAfterPersistenceRepairPoolExhaustion(
            Vertx vertx,
            VertxTestContext testContext) {
        String testTopic = newTopic("stuck-recovery-after-repair-exhaustion");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();

        producer.send("Message recovered after persistence repair exhausts its retries")
            .compose(v -> findMessageId(testTopic))
            .compose(messageId -> manager.getDatabaseService().getConnectionProvider()
                    .getReactivePool("peegeeq-main")
                    .compose(applicationPool -> runStuckRecoveryAfterRepairExhaustion(
                            vertx,
                            testTopic,
                            messageId,
                            applicationPool,
                            attemptCount)))
            .onSuccess(result -> testContext.verify(() -> {
                assertEquals("PROCESSING", result.stuckRow().getString("status"));
                assertEquals(0, result.stuckRow().getInteger("retry_count"));
                assertEquals(1, result.recoveredCount(),
                        "The recovery manager must repair exactly the stranded message");
                assertPersistedState(result.completedRow(), attemptCount, 2, "COMPLETED", 0);
                assertEquals(0, result.deadLetterCount(),
                        "Stuck-message recovery must not create a dead-letter record");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Handler-reported rollback reaches dead letter consistently")
    void testTransactionRollbackDuringRetryStateUpdate(Vertx vertx, VertxTestContext testContext) {
        assertDeadLetterScenario(
                vertx,
                testContext,
                "transaction-rollback",
                "Transaction rollback during retry update");
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Connection loss during retry persistence does not strand the message")
    void testConnectionLossDuringRetryPersistenceDoesNotStrandMessage(
            Vertx vertx,
            VertxTestContext testContext) {
        String testTopic = newTopic("retry-persistence-connection-loss");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();
        AtomicBoolean injectFault = new AtomicBoolean(true);
        AtomicReference<LockedRow> lockedRow = new AtomicReference<>();

        producer.send("Message whose first retry update loses its database connection")
            .compose(v -> findMessageId(testTopic))
            .compose(messageId -> runRetryPersistenceConnectionFault(
                    vertx,
                    testTopic,
                    messageId,
                    attemptCount,
                    injectFault,
                    lockedRow)
                    .eventually(() -> releaseLockedRow(lockedRow)))
            .onSuccess(result -> testContext.verify(() -> {
                assertPersistedState(result.outboxRow(), attemptCount, 5, "DEAD_LETTER", 3);
                assertEquals(1, result.deadLetterCount(),
                        "Connection recovery must produce exactly one durable dead-letter record");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Connection loss inside the DLQ transaction rolls back and recovers")
    void testConnectionLossInsideDeadLetterTransactionRollsBackAndRecovers(
            Vertx vertx,
            VertxTestContext testContext) {
        String testTopic = newTopic("dlq-transaction-connection-loss");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();
        AtomicBoolean injectFault = new AtomicBoolean(true);
        AtomicReference<LockedRow> lockedRow = new AtomicReference<>();

        producer.send("Message whose first DLQ transaction loses its database connection")
            .compose(v -> findMessageId(testTopic))
            .compose(messageId -> runDeadLetterTransactionConnectionFault(
                    vertx,
                    testTopic,
                    messageId,
                    attemptCount,
                    injectFault,
                    lockedRow)
                    .eventually(() -> releaseLockedRow(lockedRow)))
            .onSuccess(result -> testContext.verify(() -> {
                assertPersistedState(result.outboxRow(), attemptCount, 5, "DEAD_LETTER", 3);
                assertEquals(1, result.deadLetterCount(),
                        "The rolled-back transaction must be followed by exactly one durable DLQ record");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Connection loss during completion persistence preserves retry budget")
    void testConnectionLossDuringCompletionPersistencePreservesRetryBudget(
            Vertx vertx,
            VertxTestContext testContext) {
        String testTopic = newTopic("completion-persistence-connection-loss");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();
        AtomicBoolean injectFault = new AtomicBoolean(true);
        AtomicReference<LockedRow> lockedRow = new AtomicReference<>();

        producer.send("Message whose first completion update loses its database connection")
            .compose(v -> findMessageId(testTopic))
            .compose(messageId -> runCompletionPersistenceConnectionFault(
                    vertx,
                    testTopic,
                    messageId,
                    attemptCount,
                    injectFault,
                    lockedRow)
                    .eventually(() -> releaseLockedRow(lockedRow)))
            .onSuccess(result -> testContext.verify(() -> {
                assertPersistedState(result.outboxRow(), attemptCount, 2, "COMPLETED", 0);
                assertEquals(0, result.deadLetterCount(),
                        "A completion persistence failure must not create a dead-letter record");
                assertEquals(1.0, failedMessageCount(testTopic),
                        "A completion persistence fault must be recorded once, not also classified as a handler failure");
                assertEquals(1.0, failedMessageCount(testTopic, "COMPLETION_FAILURE"),
                        "The persistence fault must retain its completion-failure classification");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("RETRY RESILIENCE: Handler recovers after two temporary failures")
    void testDatabaseRecoveryAfterTemporaryFailure(Vertx vertx, VertxTestContext testContext) {
        String testTopic = newTopic("database-recovery");
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        AtomicInteger attemptCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt <= 2) {
                return Future.failedFuture(new RuntimeException(
                        "INTENTIONAL FAILURE: Database temporarily unavailable, attempt " + attempt));
            }
            successCount.incrementAndGet();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message for database recovery test"))
            .compose(v -> awaitTerminalState(vertx, testTopic, "COMPLETED", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertPersistedState(row, attemptCount, 3, "COMPLETED", 2);
                assertEquals(1, successCount.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private void assertDeadLetterScenario(
            Vertx vertx,
            VertxTestContext testContext,
            String topicSuffix,
            String failureDescription) {
        String testTopic = newTopic(topicSuffix);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            return Future.failedFuture(new RuntimeException(
                    "INTENTIONAL FAILURE: " + failureDescription + ", attempt " + attempt));
        })
            .compose(v -> producer.send("Message for " + topicSuffix))
            .compose(v -> awaitTerminalState(vertx, testTopic, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertPersistedState(row, attemptCount, 4, "DEAD_LETTER", 3);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Row> awaitTerminalState(
            Vertx vertx,
            String testTopic,
            String expectedStatus,
            int remainingAttempts) {
        return verificationPool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY id DESC
                        LIMIT 1
                        """).execute(Tuple.of(testTopic)))
                .compose(rows -> {
                    if (rows.size() == 1) {
                        Row row = rows.iterator().next();
                        if (expectedStatus.equals(row.getString("status"))) {
                            return Future.succeededFuture(row);
                        }
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Message on topic " + testTopic + " did not reach " + expectedStatus);
                    }
                    return vertx.timer(100).compose(v -> awaitTerminalState(
                            vertx, testTopic, expectedStatus, remainingAttempts - 1));
                });
    }

    private Future<RetryPersistenceResult> runRetryPersistenceConnectionFault(
            Vertx vertx,
            String testTopic,
            long messageId,
            AtomicInteger attemptCount,
            AtomicBoolean injectFault,
            AtomicReference<LockedRow> lockedRow) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                + "The first retry-state UPDATE will lose its PostgreSQL backend connection");

        return consumer.subscribe(message -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (injectFault.compareAndSet(true, false)) {
                        return lockOutboxRow(messageId)
                                .map(lock -> {
                                    lockedRow.set(lock);
                                    return (Void) null;
                                })
                                .compose(v -> Future.failedFuture(new RuntimeException(
                                        "INTENTIONAL FAILURE: trigger retry persistence connection fault")));
                    }
                    return Future.failedFuture(new RuntimeException(
                            "INTENTIONAL FAILURE: continue retry sequence after connection recovery, attempt "
                                    + attempt));
                })
                .compose(v -> terminateBlockedRetryUpdate(vertx, 200))
                .compose(terminatedCount -> {
                    assertEquals(1, terminatedCount,
                            "Exactly one backend executing the blocked retry update must be terminated");
                    return releaseLockedRow(lockedRow);
                })
                .compose(v -> awaitTerminalState(vertx, testTopic, "DEAD_LETTER", 100))
                .compose(outboxRow -> countDeadLetters(testTopic)
                        .map(deadLetterCount -> new RetryPersistenceResult(outboxRow, deadLetterCount)));
    }

    private Future<RetryPersistenceResult> runDeadLetterTransactionConnectionFault(
            Vertx vertx,
            String testTopic,
            long messageId,
            AtomicInteger attemptCount,
            AtomicBoolean injectFault,
            AtomicReference<LockedRow> lockedRow) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                + "The first DLQ transaction will lose its PostgreSQL backend connection after the insert");

        return consumer.subscribe(message -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt >= 4 && injectFault.compareAndSet(true, false)) {
                        return lockOutboxRow(messageId)
                                .map(lock -> {
                                    lockedRow.set(lock);
                                    return (Void) null;
                                })
                                .compose(v -> Future.failedFuture(new RuntimeException(
                                        "INTENTIONAL FAILURE: trigger DLQ transaction connection fault")));
                    }
                    return Future.failedFuture(new RuntimeException(
                            "INTENTIONAL FAILURE: DLQ transaction recovery sequence, attempt " + attempt));
                })
                .compose(v -> terminateBlockedDeadLetterUpdate(vertx, 200))
                .compose(terminatedCount -> {
                    assertEquals(1, terminatedCount,
                            "Exactly one backend executing the blocked DLQ status update must be terminated");
                    return countDeadLetters(testTopic);
                })
                .compose(countAfterTermination -> {
                    assertEquals(0, countAfterTermination,
                            "Terminating the DLQ transaction must roll back its uncommitted insert");
                    return releaseLockedRow(lockedRow);
                })
                .compose(v -> awaitTerminalState(vertx, testTopic, "DEAD_LETTER", 100))
                .compose(outboxRow -> vertx.timer(300)
                        .compose(v -> countDeadLetters(testTopic))
                        .map(deadLetterCount -> new RetryPersistenceResult(outboxRow, deadLetterCount)));
    }

    private Future<RetryPersistenceResult> runCompletionPersistenceConnectionFault(
            Vertx vertx,
            String testTopic,
            long messageId,
            AtomicInteger attemptCount,
            AtomicBoolean injectFault,
            AtomicReference<LockedRow> lockedRow) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                + "The first completion UPDATE will lose its PostgreSQL backend connection");

        return consumer.subscribe(message -> {
                    attemptCount.incrementAndGet();
                    if (injectFault.compareAndSet(true, false)) {
                        return lockOutboxRow(messageId)
                                .map(lock -> {
                                    lockedRow.set(lock);
                                    return (Void) null;
                                });
                    }
                    return Future.succeededFuture();
                })
                .compose(v -> terminateBlockedCompletionUpdate(vertx, 200))
                .compose(terminatedCount -> {
                    assertEquals(1, terminatedCount,
                            "Exactly one backend executing the blocked completion update must be terminated");
                    return releaseLockedRow(lockedRow);
                })
                .compose(v -> awaitTerminalState(vertx, testTopic, "COMPLETED", 100))
                .compose(outboxRow -> vertx.timer(300)
                        .compose(v -> countDeadLetters(testTopic))
                        .map(deadLetterCount -> new RetryPersistenceResult(outboxRow, deadLetterCount)));
    }

    private Future<StuckRecoveryResult> runStuckRecoveryAfterRepairExhaustion(
            Vertx vertx,
            String testTopic,
            long messageId,
            Pool applicationPool,
            AtomicInteger attemptCount) {
        List<SqlConnection> heldConnections = new ArrayList<>();
        AtomicBoolean connectionsReleased = new AtomicBoolean();

        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                + "The application pool will remain exhausted through every bounded persistence-repair attempt");

        return acquireConnections(applicationPool, APPLICATION_POOL_SIZE - 1, heldConnections)
                .compose(v -> consumer.subscribe(message -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt == 1) {
                        return applicationPool.getConnection()
                                .compose(connection -> {
                                    heldConnections.add(connection);
                                    return Future.failedFuture(new RuntimeException(
                                            "INTENTIONAL FAILURE: strand message while persistence repair is exhausted"));
                                });
                    }
                    return Future.succeededFuture();
                }))
                .compose(v -> vertx.timer(700).mapEmpty())
                .compose(v -> assertPoolAcquisitionFails(applicationPool))
                .compose(v -> awaitTerminalState(vertx, testTopic, "PROCESSING", 0))
                .compose(stuckRow -> closeAllOnce(heldConnections, connectionsReleased)
                        .compose(v -> ageProcessingMessageForRecovery(messageId))
                        .compose(v -> manager.getStuckMessageRecoveryManager().recoverStuckMessages())
                        .compose(recoveredCount -> awaitTerminalState(vertx, testTopic, "COMPLETED", 100)
                                .compose(completedRow -> countDeadLetters(testTopic)
                                        .map(deadLetterCount -> new StuckRecoveryResult(
                                                stuckRow,
                                                recoveredCount,
                                                completedRow,
                                                deadLetterCount)))))
                .eventually(() -> closeAllOnce(heldConnections, connectionsReleased));
    }

    private Future<Void> ageProcessingMessageForRecovery(long messageId) {
        return verificationPool.preparedQuery("""
                        UPDATE outbox
                        SET processed_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                        WHERE id = $1 AND status = 'PROCESSING'
                        """)
                .execute(Tuple.of(messageId))
                .compose(result -> result.rowCount() == 1
                        ? Future.succeededFuture()
                        : Future.failedFuture(new AssertionError(
                                "Expected exactly one PROCESSING message to age for stuck recovery")));
    }

    private Future<Long> findMessageId(String testTopic) {
        return verificationPool.preparedQuery("""
                        SELECT id
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY id DESC
                        LIMIT 1
                        """)
                .execute(Tuple.of(testTopic))
                .compose(rows -> rows.size() == 1
                        ? Future.succeededFuture(rows.iterator().next().getLong("id"))
                        : Future.failedFuture("Expected one outbox row for topic " + testTopic));
    }

    private Future<LockedRow> lockOutboxRow(long messageId) {
        return verificationPool.getConnection()
                .compose(connection -> connection.begin()
                        .compose(transaction -> connection.preparedQuery(
                                        "SELECT id FROM outbox WHERE id = $1 FOR UPDATE")
                                .execute(Tuple.of(messageId))
                                .map(rows -> new LockedRow(connection, transaction)))
                        .transform(result -> result.succeeded()
                                ? Future.succeededFuture(result.result())
                                : connection.close().compose(v -> Future.failedFuture(result.cause()))));
    }

    private Future<Integer> terminateBlockedRetryUpdate(Vertx vertx, int remainingAttempts) {
        return verificationPool.query("""
                        SELECT pg_terminate_backend(pid) AS terminated
                        FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND datname = current_database()
                          AND state = 'active'
                          AND wait_event_type = 'Lock'
                          AND query LIKE 'UPDATE %outbox SET retry_count = $1%'
                        """)
                .execute()
                .compose(rows -> {
                    int terminatedCount = 0;
                    for (Row row : rows) {
                        if (Boolean.TRUE.equals(row.getBoolean("terminated"))) {
                            terminatedCount++;
                        }
                    }
                    if (terminatedCount > 0) {
                        return Future.succeededFuture(terminatedCount);
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Did not observe a retry-state UPDATE blocked on the controlled row lock");
                    }
                    return vertx.timer(25)
                            .compose(v -> terminateBlockedRetryUpdate(vertx, remainingAttempts - 1));
                });
    }

    private Future<Integer> terminateBlockedDeadLetterUpdate(Vertx vertx, int remainingAttempts) {
        return verificationPool.query("""
                        SELECT pg_terminate_backend(pid) AS terminated
                        FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND datname = current_database()
                          AND state = 'active'
                          AND wait_event_type = 'Lock'
                          AND query LIKE 'UPDATE %outbox SET status = ''DEAD_LETTER'', error_message = $1%'
                        """)
                .execute()
                .compose(rows -> {
                    int terminatedCount = 0;
                    for (Row row : rows) {
                        if (Boolean.TRUE.equals(row.getBoolean("terminated"))) {
                            terminatedCount++;
                        }
                    }
                    if (terminatedCount > 0) {
                        return Future.succeededFuture(terminatedCount);
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Did not observe a DLQ status UPDATE blocked after its transactional insert");
                    }
                    return vertx.timer(25)
                            .compose(v -> terminateBlockedDeadLetterUpdate(
                                    vertx,
                                    remainingAttempts - 1));
                });
    }

    private Future<Integer> terminateBlockedCompletionUpdate(Vertx vertx, int remainingAttempts) {
        return verificationPool.query("""
                        SELECT pg_terminate_backend(pid) AS terminated
                        FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND datname = current_database()
                          AND state = 'active'
                          AND wait_event_type = 'Lock'
                          AND query LIKE 'UPDATE %outbox SET status = ''COMPLETED'', processed_at = $1%'
                        """)
                .execute()
                .compose(rows -> {
                    int terminatedCount = 0;
                    for (Row row : rows) {
                        if (Boolean.TRUE.equals(row.getBoolean("terminated"))) {
                            terminatedCount++;
                        }
                    }
                    if (terminatedCount > 0) {
                        return Future.succeededFuture(terminatedCount);
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Did not observe a completion UPDATE blocked on the controlled row lock");
                    }
                    return vertx.timer(25)
                            .compose(v -> terminateBlockedCompletionUpdate(
                                    vertx,
                                    remainingAttempts - 1));
                });
    }

    private Future<Integer> countDeadLetters(String testTopic) {
        return verificationPool.preparedQuery("""
                        SELECT CAST(COUNT(*) AS INTEGER) AS dead_letter_count
                        FROM dead_letter_queue
                        WHERE topic = $1
                        """)
                .execute(Tuple.of(testTopic))
                .map(rows -> rows.iterator().next().getInteger("dead_letter_count"));
    }

    private double failedMessageCount(String testTopic) {
        return meterRegistry.find("peegeeq.messages.failed.by.topic")
                .tag("topic", testTopic)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double failedMessageCount(String testTopic, String errorType) {
        return meterRegistry.find("peegeeq.messages.failed.by.topic")
                .tag("topic", testTopic)
                .tag("error_type", errorType)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private Future<Void> releaseLockedRow(AtomicReference<LockedRow> lockedRowReference) {
        LockedRow lock = lockedRowReference.getAndSet(null);
        if (lock == null) {
            return Future.succeededFuture();
        }
        return lock.transaction().rollback()
                .eventually(lock.connection()::close);
    }

    private Future<Row> exhaustPoolAndAssertConsumerRecovery(
            Vertx vertx,
            String testTopic,
            Pool applicationPool,
            AtomicInteger attemptCount) {
        List<SqlConnection> heldConnections = new ArrayList<>();
        AtomicBoolean connectionReleased = new AtomicBoolean();

        return acquireConnections(applicationPool, APPLICATION_POOL_SIZE, heldConnections)
                .compose(v -> assertConsumerRecoversAfterPoolRelease(
                        vertx,
                        testTopic,
                        applicationPool,
                        heldConnections,
                        connectionReleased,
                        attemptCount))
                .eventually(() -> closeAllOnce(heldConnections, connectionReleased));
    }

    private Future<Row> assertConsumerRecoversAfterPoolRelease(
            Vertx vertx,
            String testTopic,
            Pool applicationPool,
            List<SqlConnection> heldConnections,
            AtomicBoolean connectionReleased,
            AtomicInteger attemptCount) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                + "The following ConnectionPoolTooBusyException polling errors are expected");

        return consumer.subscribe(message -> {
                    attemptCount.incrementAndGet();
                    return Future.succeededFuture();
                })
                .compose(v -> assertPoolAcquisitionFails(applicationPool))
                .compose(v -> vertx.timer(300).mapEmpty())
                .compose(v -> {
                    assertEquals(0, attemptCount.intValue(),
                            "The handler must not run while every application connection is held");
                    return closeAllOnce(heldConnections, connectionReleased);
                })
                .compose(v -> awaitTerminalState(vertx, testTopic, "COMPLETED", 100));
    }

    private Future<List<SqlConnection>> acquireConnections(
            Pool applicationPool,
            int remaining,
            List<SqlConnection> heldConnections) {
        if (remaining == 0) {
            return Future.succeededFuture(heldConnections);
        }
        return applicationPool.getConnection()
                .compose(connection -> {
                    heldConnections.add(connection);
                    return acquireConnections(applicationPool, remaining - 1, heldConnections);
                });
    }

    private Future<Void> assertPoolAcquisitionFails(Pool applicationPool) {
        return applicationPool.getConnection().transform(result -> {
            if (result.succeeded()) {
                return result.result().close()
                        .compose(v -> Future.failedFuture(new AssertionError(
                                "An additional application connection must be refused while the pool is exhausted")));
            }

            assertNotNull(result.cause(), "Pool exhaustion must expose an acquisition failure");
            assertInstanceOf(ConnectionPoolTooBusyException.class, result.cause(),
                    "The fully occupied, zero-wait application pool must reject acquisition as too busy");
            return Future.succeededFuture();
        });
    }

    private Future<Void> closeAllOnce(
            List<SqlConnection> connections,
            AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        return Future.all(connections.stream().map(SqlConnection::close).toList()).mapEmpty();
    }

    private void assertPersistedState(
            Row row,
            AtomicInteger attemptCount,
            int expectedAttempts,
            String expectedStatus,
            int expectedRetryCount) {
        assertEquals(expectedAttempts, attemptCount.intValue());
        assertEquals(expectedStatus, row.getString("status"));
        assertEquals(expectedRetryCount, row.getInteger("retry_count"));
    }

    private String newTopic(String suffix) {
        return "test-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record LockedRow(SqlConnection connection, Transaction transaction) {
    }

    private record RetryPersistenceResult(Row outboxRow, int deadLetterCount) {
    }

    private record StuckRecoveryResult(
            Row stuckRow,
            int recoveredCount,
            Row completedRow,
            int deadLetterCount) {
    }
}
