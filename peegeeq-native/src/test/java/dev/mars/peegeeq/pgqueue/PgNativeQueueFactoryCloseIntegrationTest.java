package dev.mars.peegeeq.pgqueue;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PgNativeQueueFactory#close()} closes every managed consumer and settles only when all of
 * them have settled, also when one of them fails.
 *
 * <p>The faults are real. One consumer's handler holds a Promise, so that consumer's close waits
 * for the in-flight message. With one consumer thread that consumer issues no further claim. A
 * second connection locks {@code queue_messages}; the other consumer's next claim waits for the
 * lock, and the test cancels exactly that claim with {@code pg_cancel_backend} (SQLSTATE 57014),
 * which fails the second consumer's close. Background queries that wait for the same lock are not
 * cancelled.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgNativeQueueFactoryCloseIntegrationTest {

    private static final long DEADLINE_MILLIS = 10_000;
    private static final String CLAIM_SQL_MARKER = "SET status = 'LOCKED'";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.NATIVE_QUEUE, SchemaComponent.DEAD_LETTER_QUEUE);

        // The lock blocks the claim and the background queries at once; each holds a pool connection.
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.max-size", "10")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("native-factory-close-test", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    factory = new PgNativeQueueFactory(databaseService, config);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueConsumer",
            message = "Error processing messages for topic close-failing-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueConsumer",
            message = "Message processing pipeline failed for topic close-failing-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueFactory",
            message = "Error closing managed native queue resource",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueFactory",
            message = "Failed to close PgNativeQueueFactory",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Close step 'close hook native-queue' failed: ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    void closeSettlesOnlyAfterEveryManagedConsumerHasSettledWhenOneFails(Vertx vertx, VertxTestContext testContext) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConsumerConfig polling = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(100))
                .batchSize(1)
                .consumerThreads(1)
                .build();
        MessageConsumer<String> slow = factory.createConsumer("close-slow-" + suffix, String.class, polling);
        MessageConsumer<String> failing = factory.createConsumer("close-failing-" + suffix, String.class, polling);
        MessageProducer<String> producer = factory.createProducer("close-slow-" + suffix, String.class);
        Promise<Void> handlerEntered = Promise.promise();
        Promise<Void> releaseHandler = Promise.promise();
        AtomicReference<Future<Void>> factoryClose = new AtomicReference<>();
        Pool pool = databaseService.getPool();

        slow.subscribe(message -> {
                    handlerEntered.tryComplete();
                    return releaseHandler.future();
                })
                .compose(v -> failing.subscribe(message -> Future.<Void>succeededFuture()))
                .compose(v -> producer.send("held by the handler"))
                .compose(v -> withDeadline(vertx, handlerEntered.future(), "The slow consumer's handler was not entered"))
                .compose(v -> pool.withTransaction(lockHolder ->
                        lockHolder.query("LOCK TABLE queue_messages IN ACCESS EXCLUSIVE MODE").execute()
                                .compose(locked -> awaitWaitingClaim(vertx, pool, System.currentTimeMillis() + DEADLINE_MILLIS))
                                .compose(waiting -> {
                                    factoryClose.set(factory.close());
                                    return cancelWaitingClaim(pool);
                                })
                                // A factory close that fails as soon as one consumer close fails settles
                                // here, while the slow consumer still holds its message.
                                .compose(cancelled -> vertx.timer(500))
                                .compose(tick -> {
                                    if (factoryClose.get().isComplete()) {
                                        return Future.<Void>failedFuture(new AssertionError(
                                                "The factory close settled while a managed consumer was still closing: "
                                                        + factoryClose.get().cause()));
                                    }
                                    return Future.<Void>succeededFuture();
                                })))
                .compose(v -> {
                    releaseHandler.complete();
                    return withDeadline(vertx,
                            factoryClose.get().<AsyncResult<Void>>transform(Future::succeededFuture),
                            "The factory close did not settle after the slow consumer was released");
                })
                .compose(outcome -> {
                    factory = null; // its close was the subject and has settled
                    assertTrue(outcome.failed(), "The factory close must report the failed consumer close");
                    PgException failure = assertInstanceOf(PgException.class, outcome.cause(),
                            "The reported failure must be the cancelled claim of the failing consumer");
                    assertEquals("57014", failure.getSqlState());
                    // The factory is a close hook of the manager, so the manager close reports it too.
                    PeeGeeQManager closing = manager;
                    manager = null;
                    return closing.closeReactive().<AsyncResult<Void>>transform(Future::succeededFuture);
                })
                .onComplete(testContext.succeeding(managerOutcome -> testContext.verify(() -> {
                    assertTrue(managerOutcome.failed(), "The manager close must report the failed factory close");
                    assertInstanceOf(PgException.class, managerOutcome.cause());
                    testContext.completeNow();
                })));
    }

    /** Completes when a claim query waits for the table lock. */
    private Future<Void> awaitWaitingClaim(Vertx vertx, Pool pool, long deadlineMillis) {
        String sql = """
                SELECT COUNT(*) AS waiting
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND position($1 in query) > 0
                """;
        return pool.preparedQuery(sql).execute(io.vertx.sqlclient.Tuple.of(CLAIM_SQL_MARKER))
                .compose(rows -> {
                    if (rows.iterator().next().getLong("waiting") > 0) {
                        return Future.<Void>succeededFuture();
                    }
                    if (System.currentTimeMillis() >= deadlineMillis) {
                        return Future.<Void>failedFuture(new AssertionError(
                                "No claim query waited for the queue_messages lock within " + DEADLINE_MILLIS + " ms"));
                    }
                    return vertx.timer(50).compose(tick -> awaitWaitingClaim(vertx, pool, deadlineMillis));
                });
    }

    /** Cancels the claim queries that wait for the table lock, and only those. */
    private Future<Void> cancelWaitingClaim(Pool pool) {
        String sql = """
                SELECT pg_cancel_backend(pid) AS cancelled
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND position($1 in query) > 0
                """;
        return pool.preparedQuery(sql).execute(io.vertx.sqlclient.Tuple.of(CLAIM_SQL_MARKER))
                .compose(rows -> rows.size() > 0
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError("The waiting claim query was gone before the cancel")));
    }

    private static <T> Future<T> withDeadline(Vertx vertx, Future<T> call, String message) {
        Promise<T> result = Promise.promise();
        long timerId = vertx.setTimer(DEADLINE_MILLIS, ignored -> result.tryFail(new AssertionError(message)));
        call
                .onSuccess(value -> {
                    vertx.cancelTimer(timerId);
                    result.tryComplete(value);
                })
                .onFailure(error -> {
                    vertx.cancelTimer(timerId);
                    result.tryFail(error);
                });
        return result.future();
    }
}
