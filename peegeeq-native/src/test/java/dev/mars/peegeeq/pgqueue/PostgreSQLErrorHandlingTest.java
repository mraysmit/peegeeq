package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL errors raised inside a native queue message handler.
 *
 * <p>Each test forces the named PostgreSQL error for real, with SQL against the container, on the
 * first delivery of a message. The handler returns that failure to the consumer. The tests assert
 * the error's SQLSTATE and what the consumer does with it: a transient error is retried and the
 * second delivery succeeds; a permanent error is retried {@code max-retries} times and the message
 * is dead-lettered with the error recorded. Nothing is simulated in Java.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PostgreSQLErrorHandlingTest {
    private static final Logger logger = LoggerFactory.getLogger(PostgreSQLErrorHandlingTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static final long POLL_DEADLINE_MS = 20_000;

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .property("peegeeq.queue.visibility-timeout", "PT10S")
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .build();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("native", databaseService);
                logger.info("Test setup completed for PostgreSQL error handling testing");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        // Both closes run; the first failure fails the teardown.
        TestCleanup.run(
                () -> factory != null ? factory.close() : Future.succeededFuture(),
                () -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Error during teardown", err);
                testContext.failNow(err);
            });
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void serializationFailureInTheHandlerIsRetried(Vertx vertx, VertxTestContext testContext) {
        assertTransientErrorIsRetried(vertx, testContext, "serialization", "40001", this::forceSerializationFailure);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void deadlockInTheHandlerIsRetried(Vertx vertx, VertxTestContext testContext) {
        assertTransientErrorIsRetried(vertx, testContext, "deadlock", "40P01", this::forceDeadlock);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void statementTimeoutInTheHandlerIsRetried(Vertx vertx, VertxTestContext testContext) {
        assertTransientErrorIsRetried(vertx, testContext, "timeout", "57014", table -> pool()
                .withTransaction(conn -> conn.query("SET LOCAL statement_timeout = '100ms'").execute()
                        .compose(set -> conn.query("SELECT pg_sleep(2)").execute()))
                .mapEmpty());
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void terminatedHandlerConnectionIsRetried(Vertx vertx, VertxTestContext testContext) {
        // The handler's own backend is terminated by the server (57P01, admin shutdown).
        assertTransientErrorIsRetried(vertx, testContext, "terminate", "57P01", table -> pool()
                .query("SELECT pg_terminate_backend(pg_backend_pid())").execute()
                .mapEmpty());
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void rolledBackFirstAttemptLeavesOnlyTheRetriedWrite(Vertx vertx, VertxTestContext testContext) {
        String topic = "pg-error-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        String table = probeTable(topic);
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
        AtomicInteger attempts = new AtomicInteger();

        createProbeTable(table)
            .compose(created -> consumer.subscribe(message -> {
                int attempt = attempts.incrementAndGet();
                // The write and the failure share one transaction, so the first attempt's row is
                // rolled back with it.
                return pool().withTransaction(conn -> conn
                        .preparedQuery("INSERT INTO " + table + " (id, v) VALUES ($1, $2)")
                        .execute(Tuple.of(100, attempt))
                        .compose(inserted -> attempt == 1
                                ? Future.<Void>failedFuture(new IllegalStateException("roll back attempt 1"))
                                : Future.<Void>succeededFuture()));
            }))
            .compose(subscribed -> producer.send("rollback message"))
            .compose(sent -> pollUntil(vertx, () -> countRows("queue_messages", topic).map(c -> c == 0L),
                    "the message was not settled"))
            .compose(settled -> pool().preparedQuery("SELECT v FROM " + table + " WHERE id = 100").execute())
            .transform(TestCleanup.cleanup(() -> dropProbeTable(table)))
            .onSuccess(rows -> testContext.verify(() -> {
                assertEquals(2, attempts.get(), "the rolled-back attempt must be delivered again once");
                assertEquals(1, rows.size(), "only the committed attempt may leave a row");
                assertEquals(2, rows.iterator().next().getInteger("v"), "the row must come from attempt 2");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 45, timeUnit = TimeUnit.SECONDS)
    void persistentUniqueViolationIsDeadLetteredWithTheError(Vertx vertx, VertxTestContext testContext) {
        String topic = "pg-error-unique-" + UUID.randomUUID().toString().substring(0, 8);
        String table = probeTable(topic);
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
        int maxRetries = manager.getConfiguration().getQueueConfig().getMaxRetries();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        createProbeTable(table)
            .compose(created -> consumer.subscribe(message -> {
                attempts.incrementAndGet();
                // Row 1 exists, so every attempt violates the primary key.
                return pool().query("INSERT INTO " + table + " (id, v) VALUES (1, 0)").execute()
                        .<Void>mapEmpty()
                        .onFailure(lastFailure::set);
            }))
            .compose(subscribed -> producer.send("duplicate key message"))
            .compose(sent -> pollUntil(vertx, () -> countRows("dead_letter_queue", topic).map(c -> c == 1L),
                    "the message was not dead-lettered"))
            .compose(dead -> pool()
                    .preparedQuery("SELECT retry_count, failure_reason FROM dead_letter_queue WHERE topic = $1")
                    .execute(Tuple.of(topic)))
            .transform(TestCleanup.cleanup(() -> dropProbeTable(table)))
            .onSuccess(rows -> testContext.verify(() -> {
                assertSqlState(lastFailure.get(), "23505");
                assertEquals(maxRetries + 1, attempts.get(),
                        "with max-retries N a failing message is delivered N + 1 times");
                var row = rows.iterator().next();
                assertEquals(maxRetries, row.getInteger("retry_count"), "the dead letter must record the retries");
                assertTrue(row.getString("failure_reason").contains("23505"),
                        "the dead letter must record the PostgreSQL error: " + row.getString("failure_reason"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Sends one message whose first delivery runs {@code failOnce}, which must fail with
     * {@code expectedSqlState}; the second delivery succeeds. Asserts two deliveries, the
     * SQLSTATE, an empty queue and no dead letter.
     */
    private void assertTransientErrorIsRetried(Vertx vertx, VertxTestContext testContext, String scenario,
                                               String expectedSqlState, Function<String, Future<Void>> failOnce) {
        String topic = "pg-error-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
        String table = probeTable(topic);
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        createProbeTable(table)
            .compose(created -> consumer.subscribe(message -> {
                if (attempts.incrementAndGet() > 1) {
                    return Future.succeededFuture();
                }
                Future<Void> forced = failOnce.apply(table);
                return forced
                    .transform(outcome -> outcome.failed()
                        ? Future.<Void>failedFuture(recordFirst(firstFailure, outcome.cause()))
                        : Future.<Void>failedFuture(new AssertionError(
                                "the " + scenario + " scenario did not raise a PostgreSQL error")));
            }))
            .compose(subscribed -> producer.send(scenario + " message"))
            .compose(sent -> pollUntil(vertx, () -> countRows("queue_messages", topic).map(c -> c == 0L),
                    "the message was not settled"))
            .compose(settled -> countRows("dead_letter_queue", topic))
            .transform(TestCleanup.cleanup(() -> dropProbeTable(table)))
            .onSuccess(deadLetters -> testContext.verify(() -> {
                assertSqlState(firstFailure.get(), expectedSqlState);
                assertEquals(2, attempts.get(), "the failed delivery must be retried exactly once");
                assertEquals(0L, deadLetters, "a retried message must not be dead-lettered");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * A REPEATABLE READ transaction takes its snapshot, another connection updates the row, and
     * the first transaction's update of that row then fails with 40001.
     */
    private Future<Void> forceSerializationFailure(String table) {
        return withTwoConnections((a, b) -> a.query("BEGIN ISOLATION LEVEL REPEATABLE READ").execute()
                .compose(begun -> a.query("SELECT v FROM " + table + " WHERE id = 1").execute())
                .compose(snapshot -> b.query("UPDATE " + table + " SET v = v + 1 WHERE id = 1").execute())
                .compose(changed -> a.query("UPDATE " + table + " SET v = v + 1 WHERE id = 1").execute())
                .<Void>mapEmpty()
                .transform(TestCleanup.cleanup(() -> a.query("ROLLBACK").execute())));
    }

    /**
     * Two transactions lock rows 1 and 2 in opposite orders. The server detects the cycle after
     * {@code deadlock_timeout} (100 ms here) and aborts one of them with 40P01.
     */
    private Future<Void> forceDeadlock(String table) {
        return withTwoConnections((a, b) -> a.query("BEGIN").execute()
                .compose(x -> a.query("SET LOCAL deadlock_timeout = '100ms'").execute())
                .compose(x -> a.query("UPDATE " + table + " SET v = v + 1 WHERE id = 1").execute())
                .compose(x -> b.query("BEGIN").execute())
                .compose(x -> b.query("SET LOCAL deadlock_timeout = '100ms'").execute())
                .compose(x -> b.query("UPDATE " + table + " SET v = v + 1 WHERE id = 2").execute())
                .compose(x -> {
                    Future<?> first = a.query("UPDATE " + table + " SET v = v + 1 WHERE id = 2").execute();
                    Future<?> second = b.query("UPDATE " + table + " SET v = v + 1 WHERE id = 1").execute();
                    return Future.join(first, second).transform(settled -> first.failed()
                            ? Future.<Void>failedFuture(first.cause())
                            : second.failed()
                                ? Future.<Void>failedFuture(second.cause())
                                : Future.<Void>succeededFuture());
                })
                .transform(TestCleanup.cleanup(
                        () -> a.query("ROLLBACK").execute(),
                        () -> b.query("ROLLBACK").execute())));
    }

    private Future<Void> withTwoConnections(java.util.function.BiFunction<SqlConnection, SqlConnection, Future<Void>> work) {
        // Each connection is closed whatever the work's outcome; a failed close is reported.
        return pool().getConnection().compose(a -> pool().getConnection()
                .compose(b -> work.apply(a, b).transform(TestCleanup.cleanup(b::close)))
                .transform(TestCleanup.cleanup(a::close)));
    }

    private static Throwable recordFirst(AtomicReference<Throwable> first, Throwable failure) {
        first.compareAndSet(null, failure);
        return failure;
    }

    private static void assertSqlState(Throwable failure, String expectedSqlState) {
        assertNotNull(failure, "the handler's SQL must have failed");
        PgException pgFailure = assertInstanceOf(PgException.class, failure,
                "the failure must come from PostgreSQL: " + failure);
        assertEquals(expectedSqlState, pgFailure.getSqlState(), "unexpected SQLSTATE: " + pgFailure.getMessage());
    }

    private Pool pool() {
        return manager.getPool();
    }

    private static String probeTable(String topic) {
        return "pg_error_probe_" + topic.substring(topic.lastIndexOf('-') + 1);
    }

    private Future<Void> createProbeTable(String table) {
        return pool().query("CREATE TABLE " + table + " (id INT PRIMARY KEY, v INT NOT NULL)").execute()
                .compose(created -> pool().query("INSERT INTO " + table + " (id, v) VALUES (1, 0), (2, 0)").execute())
                .mapEmpty();
    }

    private Future<Void> dropProbeTable(String table) {
        return pool().query("DROP TABLE IF EXISTS " + table).execute().mapEmpty();
    }

    private Future<Long> countRows(String tableName, String topic) {
        return pool().preparedQuery("SELECT COUNT(*) AS c FROM " + tableName + " WHERE topic = $1")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("c"));
    }

    private Future<Void> pollUntil(Vertx vertx, Supplier<Future<Boolean>> condition, String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(POLL_DEADLINE_MS);
        Promise<Void> done = Promise.promise();
        pollOnce(vertx, condition, deadline, failureMessage, done);
        return done.future();
    }

    private void pollOnce(Vertx vertx, Supplier<Future<Boolean>> condition, long deadline,
                          String failureMessage, Promise<Void> done) {
        condition.get()
            .onSuccess(reached -> {
                if (reached) {
                    done.tryComplete();
                } else if (System.nanoTime() >= deadline) {
                    done.tryFail(new AssertionError(failureMessage));
                } else {
                    vertx.setTimer(100, id -> pollOnce(vertx, condition, deadline, failureMessage, done));
                }
            })
            .onFailure(done::tryFail);
    }
}
