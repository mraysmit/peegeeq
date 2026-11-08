package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Wiring verification for {@code PartitionedConsumerEngine} inside the outbox subsystem.
 *
 * <p>Mirrors {@code peegeeq-native}'s
 * {@code PartitionedNativeConsumerIntegrationTest#testStart_offsetWatermarkTopic_autoJoins}:
 * starting a consumer group on an OFFSET_WATERMARK topic must cause the engine to join
 * the partitioned group, which is visible as one or more rows in
 * {@code outbox_partition_assignments}. If the engine is not wired in, the row count
 * stays at zero that is exactly what this test catches.</p>
 *
 * <p>Uses Vert.x 5 reactive APIs exclusively and avoids blocking bridges and
 * thread-based delays.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox OFFSET_WATERMARK engine wiring verification")
class OutboxOffsetWatermarkWiringTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxOffsetWatermarkWiringTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.QUEUE_ALL,
                SchemaComponent.CONSUMER_GROUP_FANOUT);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    databaseService = new PgDatabaseService(manager);
                    QueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("outbox", (DatabaseService) databaseService);
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        Future<Void> closeFactory = factory != null ? factory.close() : Future.succeededFuture();
        closeFactory
                .transform(factoryResult -> {
                    Future<Void> closeManager = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return closeManager.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Starting a consumer group on an OFFSET_WATERMARK topic must cause the engine
     * to join the partitioned group, leaving rows in {@code outbox_partition_assignments}.
     */
    @Test
    @DisplayName("ConsumerGroup.start on OFFSET_WATERMARK topic creates partition assignments")
    void start_offsetWatermarkTopic_createsPartitionAssignments(Vertx vertx,
                                                                VertxTestContext testContext) throws Exception {
        String topic = "owm-wiring-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-wiring-group";

        // Configure OFFSET_WATERMARK topic + subscription, seed three partitions.
        configureOffsetWatermarkTopic(topic, groupName)
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "payload-2"))
                .compose(v -> insertOutboxMessage(topic, "part-C", "payload-3"))
                .compose(v -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.fromBeginning())
                            // The join is asynchronous; poll for the assignment rows.
                            .compose(started -> pollUntil(vertx,
                                    () -> countPartitionAssignments(topic, groupName).map(count -> count >= 1),
                                    "the engine's partition assignments"))
                            .compose(joined -> countPartitionAssignments(topic, groupName))
                            .map(count -> {
                                logger.info("partition assignments after start: {}", count);
                                assertTrue(count >= 1,
                                        "OutboxConsumerGroup must wire PartitionedConsumerEngine on " +
                                        "OFFSET_WATERMARK topics expected >= 1 row in " +
                                        "outbox_partition_assignments, got " + count);
                                return (Void) null;
                            })
                            .transform(TestCleanup.cleanup(() -> group.stopGracefully()));
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    /**
     * The engine's leave fails: an EXCLUSIVE lock on outbox_topic_subscriptions holds the leave
     * at its generation bump, and only that query is cancelled (SQLSTATE 57014). The group's
     * close must report the failure and log it at ERROR, because an engine that failed to stop
     * may still fetch and dispatch. {@code close()} is used, not {@code stopGracefully()}:
     * stopGracefully also writes the subscription status, which the lock would hold first.
     */
    @Test
    @DisplayName("close reports a failed engine leave and logs it at ERROR")
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.outbox.OutboxConsumerGroup",
            message = "Error stopping partitioned engine for group 'owm-leave-fail-group': ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @io.vertx.junit5.Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void close_reportsFailedEngineLeave(Vertx vertx, VertxTestContext testContext) {
        String topic = "owm-leave-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-leave-fail-group";
        AtomicReference<Future<Void>> close = new AtomicReference<>();

        configureOffsetWatermarkTopic(topic, groupName)
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> {
                    // Built directly, with the wiring the registrar uses, and not through the
                    // factory: the factory would report this group's failed close again in the
                    // teardown. The native counterpart builds its group the same way.
                    ConsumerGroup<String> group = new OutboxConsumerGroup<>(groupName, topic, String.class,
                            databaseService, new com.fasterxml.jackson.databind.ObjectMapper(),
                            databaseService.getMetricsProvider(), manager.getConfiguration(), null,
                            databaseService.getClientFactory().getConnectionManager(),
                            dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID);
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    return group.start(SubscriptionOptions.fromBeginning())
                            .compose(started -> pollUntil(vertx,
                                    () -> countPartitionAssignments(topic, groupName).map(count -> count >= 1),
                                    "the engine's partition assignments"))
                            .compose(joined -> databaseService.getPool().withTransaction(lockHolder ->
                                    lockHolder.query("LOCK TABLE outbox_topic_subscriptions IN EXCLUSIVE MODE")
                                            .execute()
                                            .compose(locked -> {
                                                close.set(group.close());
                                                return pollUntil(vertx,
                                                        () -> waitingQueries(GENERATION_BUMP_MARKER).map(n -> n > 0),
                                                        "the group leave waiting on the lock");
                                            })
                                            .compose(leaveWaiting -> cancelWaitingQueries(GENERATION_BUMP_MARKER))));
                })
                // The lock is released when the transaction ends; then the close settles.
                .compose(lockReleased -> close.get().<AsyncResult<Void>>transform(Future::succeededFuture))
                .onSuccess(outcome -> testContext.verify(() -> {
                    assertTrue(outcome.failed(), "close() must report the failed engine leave");
                    assertTrue(hasSqlState(outcome.cause(), "57014"),
                            "the reported failure must be the cancelled leave, but was: " + outcome.cause());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private static final String GENERATION_BUMP_MARKER = "rebalance_generation = rebalance_generation + 1";

    /** Number of backends whose query contains {@code marker} and that wait for a lock. */
    private Future<Long> waitingQueries(String marker) {
        return databaseService.getPool().preparedQuery(
                        "SELECT COUNT(*) AS waiting FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                        + "AND position($1 in query) > 0")
                .execute(Tuple.of(marker))
                .map(rows -> rows.iterator().next().getLong("waiting"));
    }

    /** Cancels the waiting backends whose query contains {@code marker}, and only those. */
    private Future<Void> cancelWaitingQueries(String marker) {
        return databaseService.getPool().preparedQuery(
                        "SELECT pg_cancel_backend(pid) AS cancelled FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                        + "AND position($1 in query) > 0")
                .execute(Tuple.of(marker))
                .compose(rows -> rows.size() > 0
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError("No waiting query to cancel for: " + marker)));
    }

    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PgException pg && sqlState.equals(pg.getSqlState())) {
                return true;
            }
            for (Throwable suppressed : t.getSuppressed()) {
                if (hasSqlState(suppressed, sqlState)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Re-runs {@code check} every 100 ms until it yields true; fails after 20 s. */
    private Future<Void> pollUntil(Vertx vertx, Supplier<Future<Boolean>> check, String awaited) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Promise<Void> done = Promise.promise();
        pollOnce(vertx, check, deadline, awaited, done);
        return done.future();
    }

    private void pollOnce(Vertx vertx, Supplier<Future<Boolean>> check, long deadline, String awaited,
                          Promise<Void> done) {
        check.get()
                .onSuccess(reached -> {
                    if (reached) {
                        done.tryComplete();
                    } else if (System.nanoTime() >= deadline) {
                        done.tryFail(new AssertionError("Timed out waiting for " + awaited));
                    } else {
                        vertx.setTimer(100, id -> pollOnce(vertx, check, deadline, awaited, done));
                    }
                })
                .onFailure(done::tryFail);
    }

    private Future<Void> configureOffsetWatermarkTopic(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK') " +
                        "ON CONFLICT (topic) DO UPDATE SET completion_tracking_mode = 'OFFSET_WATERMARK'")
                        .execute(Tuple.of(topic))
                        .compose(r -> conn.preparedQuery(
                                "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                                "VALUES ($1, $2, 'ACTIVE') " +
                                "ON CONFLICT (topic, group_name) DO NOTHING")
                                .execute(Tuple.of(topic, groupName)))
                        .mapEmpty());
    }

    private Future<Void> insertOutboxMessage(String topic, String messageGroup, String payload) {
        io.vertx.core.json.JsonObject payloadJson = new io.vertx.core.json.JsonObject().put("data", payload);
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                        "VALUES ($1, $2, 'PENDING', $3, NOW())")
                        .execute(Tuple.of(topic, payloadJson, messageGroup))
                        .mapEmpty());
    }

    private Future<Integer> countPartitionAssignments(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                        "WHERE topic = $1 AND group_name = $2")
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> rows.iterator().next().getInteger("cnt")));
    }
}
