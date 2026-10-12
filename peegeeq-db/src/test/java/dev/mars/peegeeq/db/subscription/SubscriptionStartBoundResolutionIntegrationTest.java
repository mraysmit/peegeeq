package dev.mars.peegeeq.db.subscription;

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

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for when {@link SubscriptionManager#subscribe} resolves a start bound.
 *
 * <p>The subscribe that creates a subscription resolves the start bound. It resolves FROM_NOW
 * against the message table of the store that the caller names: {@code outbox} or
 * {@code queue_messages}. A subscribe on an existing subscription keeps the stored bound, so it
 * resolves nothing and reads no message table.</p>
 *
 * <p>This class has a schema of its own. Some tests hold an exclusive lock on a message
 * table, and that lock must not block the tests that share the module schema.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class SubscriptionStartBoundResolutionIntegrationTest extends BaseIntegrationTest {

    private static final String SCHEMA = "peegeeq_start_bound_test";
    private static final String SERVICE_ID = "start-bound-test";
    private static final long LOCK_WAIT_MILLIS = 5_000;

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = getPostgres();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA,
            SchemaComponent.OUTBOX, SchemaComponent.NATIVE_QUEUE, SchemaComponent.CONSUMER_GROUP_FANOUT);

        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool(SERVICE_ID, connectionConfig, poolConfig);

        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);
        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void subscribeFromNowResolvesAgainstOutboxForTheOutboxStore(VertxTestContext testContext) {
        assertFromNowResolvesAgainstTheTableOfTheStore(testContext, SubscriptionStore.OUTBOX);
    }

    @Test
    void subscribeFromNowResolvesAgainstQueueMessagesForTheNativeStore(VertxTestContext testContext) {
        assertFromNowResolvesAgainstTheTableOfTheStore(testContext, SubscriptionStore.NATIVE);
    }

    @Test
    void subscribeOnExistingSubscriptionReadsNoMessageTableForTheOutboxStore(VertxTestContext testContext) {
        assertExistingSubscriptionReadsNoMessageTable(testContext, SubscriptionStore.OUTBOX);
    }

    @Test
    void subscribeOnExistingSubscriptionReadsNoMessageTableForTheNativeStore(VertxTestContext testContext) {
        assertExistingSubscriptionReadsNoMessageTable(testContext, SubscriptionStore.NATIVE);
    }

    @Test
    void subscribeFailsAndCreatesNoRowWhenTheOutboxPositionQueryFails(VertxTestContext testContext) {
        assertFailedPositionQueryCreatesNoRow(testContext, SubscriptionStore.OUTBOX);
    }

    @Test
    void subscribeFailsAndCreatesNoRowWhenTheQueueMessagesPositionQueryFails(VertxTestContext testContext) {
        assertFailedPositionQueryCreatesNoRow(testContext, SubscriptionStore.NATIVE);
    }

    @Test
    void subscribeWithoutAStoreIsRejectedAndCreatesNoRow(VertxTestContext testContext) {
        String topic = uniqueName("bound-no-store");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> {
                assertThrows(NullPointerException.class,
                    () -> subscriptionManager.subscribe(topic, groupName, null, SubscriptionOptions.defaults()),
                    "A subscribe must name its store");
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNull(subscription, "A rejected subscribe must store no subscription");
                testContext.completeNow();
            })));
    }

    @Test
    void concurrentFirstSubscribesStoreOneStartBound(VertxTestContext testContext) {
        String topic = uniqueName("bound-concurrent");
        String groupName = uniqueName("group");
        AtomicReference<Long> storedBound = new AtomicReference<>();

        createTopic(topic)
            .compose(v -> Future.all(
                subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(5)),
                subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(9))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(subscription -> {
                Long bound = subscription.startFromMessageId();
                assertTrue(Long.valueOf(5L).equals(bound) || Long.valueOf(9L).equals(bound),
                    "The stored start bound must be the bound of one of the two calls, but was " + bound);
                storedBound.set(bound);
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(7));
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(storedBound.get(), subscription.startFromMessageId(),
                    "A later subscribe must keep the bound that the first subscribe stored");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeKeepsTheBoundOfAConcurrentCreator(VertxTestContext testContext) {
        String topic = uniqueName("bound-race");
        String groupName = uniqueName("group");
        AtomicReference<Future<Void>> waitingSubscribe = new AtomicReference<>();
        String insertSql = """
            INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status, start_from_message_id)
            VALUES ($1, $2, 'ACTIVE', $3)
            """;

        createTopic(topic)
            // The first transaction inserts the subscription and stays open. The subscribe of the
            // second caller reads no row and then waits on the uncommitted row at its own insert.
            // The first transaction commits only after the second caller is seen waiting.
            .compose(v -> connectionManager.withTransaction(SERVICE_ID, creator ->
                creator.preparedQuery(insertSql)
                    .execute(Tuple.of(topic, groupName, 5L))
                    .compose(inserted -> {
                        waitingSubscribe.set(subscriptionManager
                            .subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(9))
                            .onFailure(testContext::failNow));
                        return awaitWaitingInsert(System.currentTimeMillis() + LOCK_WAIT_MILLIS);
                    })))
            .compose(v -> withDeadline(waitingSubscribe.get(),
                "The subscribe did not answer after the first creator committed"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(5L, subscription.startFromMessageId(),
                    "The start bound of the first creator must be kept");
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                testContext.completeNow();
            })));
    }

    /**
     * The topic has rows in both message tables, and its next id differs between them. A
     * subscribe with FROM_NOW must store the next id of the table of its store.
     */
    private void assertFromNowResolvesAgainstTheTableOfTheStore(VertxTestContext testContext,
                                                                SubscriptionStore store) {
        String topic = uniqueName("bound-from-now");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> insertMessages("outbox", topic, 2))
            .compose(v -> insertMessages("queue_messages", topic, 5))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, store, SubscriptionOptions.defaults()))
            .compose(v -> Future.all(
                nextMessageId("outbox", topic),
                nextMessageId("queue_messages", topic),
                subscriptionManager.getSubscription(topic, groupName)))
            .onComplete(testContext.succeeding(results -> testContext.verify(() -> {
                long nextOutboxId = results.resultAt(0);
                long nextQueueMessageId = results.resultAt(1);
                SubscriptionInfo subscription = results.resultAt(2);
                assertNotEquals(nextOutboxId, nextQueueMessageId,
                    "The test needs different next ids in the two message tables");
                long expected = store == SubscriptionStore.NATIVE ? nextQueueMessageId : nextOutboxId;
                assertEquals(expected, subscription.startFromMessageId(),
                    "FROM_NOW must resolve to the next id of the topic in " + messageTable(store)
                        + ": outbox=" + nextOutboxId + ", queue_messages=" + nextQueueMessageId);
                testContext.completeNow();
            })));
    }

    /**
     * A transaction holds an exclusive lock on both message tables until the subscribe has
     * answered. A subscribe that read a message table would wait for the lock.
     */
    private void assertExistingSubscriptionReadsNoMessageTable(VertxTestContext testContext,
                                                               SubscriptionStore store) {
        String topic = uniqueName("bound-existing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, store, fromMessageId(5)))
            .compose(v -> connectionManager.withTransaction(SERVICE_ID, lockHolder ->
                lockHolder.query("LOCK TABLE outbox, queue_messages IN ACCESS EXCLUSIVE MODE").execute()
                    .compose(locked -> withDeadline(
                        subscriptionManager.subscribe(topic, groupName, store, SubscriptionOptions.defaults()),
                        "A subscribe with FROM_NOW on an existing subscription waited for a message table"))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(5L, subscription.startFromMessageId(),
                    "The stored start bound must be kept");
                testContext.completeNow();
            })));
    }

    /**
     * A transaction holds an exclusive lock on the message table of the store. The subscribe
     * waits at its position query, and the query is cancelled. The subscribe must fail with
     * the failure of the query, and it must store no subscription.
     */
    private void assertFailedPositionQueryCreatesNoRow(VertxTestContext testContext, SubscriptionStore store) {
        String topic = uniqueName("bound-query-fails");
        String groupName = uniqueName("group");
        String table = messageTable(store);

        createTopic(topic)
            .compose(v -> connectionManager.withTransaction(SERVICE_ID, lockHolder ->
                lockHolder.query("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE").execute()
                    .compose(locked -> {
                        Future<Void> subscribe = subscriptionManager.subscribe(
                            topic, groupName, store, SubscriptionOptions.defaults());
                        return cancelQueryWaitingFor(table, System.currentTimeMillis() + LOCK_WAIT_MILLIS)
                            .compose(cancelled -> withDeadline(
                                subscribe.transform(result -> {
                                    if (result.succeeded()) {
                                        return Future.<Void>failedFuture(new AssertionError(
                                            "A subscribe must fail when its position query fails"));
                                    }
                                    PgException failure = assertInstanceOf(PgException.class, result.cause(),
                                        "The failure of the position query must be returned");
                                    assertEquals("57014", failure.getSqlState(),
                                        "The failure must be the cancelled position query");
                                    return Future.<Void>succeededFuture();
                                }),
                                "The subscribe did not answer after its position query was cancelled"));
                    })))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNull(subscription, "A failed creation must store no subscription");
                testContext.completeNow();
            })));
    }

    private static String messageTable(SubscriptionStore store) {
        return store == SubscriptionStore.NATIVE ? "queue_messages" : "outbox";
    }

    private Future<Void> insertMessages(String table, String topic, int count) {
        String sql = "INSERT INTO " + table + " (topic, payload) "
            + "SELECT $1::varchar, '{}'::jsonb FROM generate_series(1, $2::int)";
        return connectionManager.withTransaction(SERVICE_ID, connection ->
            connection.preparedQuery(sql).execute(Tuple.of(topic, count)).mapEmpty());
    }

    private Future<Long> nextMessageId(String table, String topic) {
        String sql = "SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM " + table + " WHERE topic = $1";
        return connectionManager.withConnection(SERVICE_ID, connection ->
            connection.preparedQuery(sql).execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("next_id")));
    }

    /** Cancels the query of the backend that waits for a lock on the table. */
    private Future<Void> cancelQueryWaitingFor(String table, long deadlineMillis) {
        String sql = """
            SELECT pg_cancel_backend(l.pid) AS cancelled
            FROM pg_locks l
            WHERE NOT l.granted
              AND l.relation = to_regclass($1)
            """;
        return connectionManager.withConnection(SERVICE_ID, connection ->
                connection.preparedQuery(sql).execute(Tuple.of(SCHEMA + "." + table)))
            .compose(rows -> {
                if (rows.size() > 0) {
                    return Future.<Void>succeededFuture();
                }
                if (System.currentTimeMillis() >= deadlineMillis) {
                    return Future.<Void>failedFuture(new AssertionError(
                        "No query waited for a lock on " + table + " within " + LOCK_WAIT_MILLIS + " ms"));
                }
                return manager.getVertx().timer(50).compose(tick -> cancelQueryWaitingFor(table, deadlineMillis));
            });
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static SubscriptionOptions fromMessageId(long messageId) {
        return SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(messageId)
            .build();
    }

    private Future<Void> createTopic(String topic) {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        return topicConfigService.createTopic(topicConfig);
    }

    /** Completes when an insert into the subscription table waits for a lock. */
    private Future<Void> awaitWaitingInsert(long deadlineMillis) {
        String sql = """
            SELECT COUNT(*) AS waiting
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND wait_event_type = 'Lock'
              AND query LIKE 'INSERT INTO outbox_topic_subscriptions%'
            """;
        return connectionManager.withConnection(SERVICE_ID, connection -> connection.query(sql).execute())
            .compose(rows -> {
                if (rows.iterator().next().getLong("waiting") > 0) {
                    return Future.<Void>succeededFuture();
                }
                if (System.currentTimeMillis() >= deadlineMillis) {
                    return Future.<Void>failedFuture(new AssertionError(
                        "No subscribe waited on the uncommitted subscription row within "
                            + LOCK_WAIT_MILLIS + " ms"));
                }
                return manager.getVertx().timer(50).compose(tick -> awaitWaitingInsert(deadlineMillis));
            });
    }

    /** Fails with the message when the call has not answered within the lock wait. */
    private <T> Future<T> withDeadline(Future<T> call, String message) {
        Vertx vertx = manager.getVertx();
        Promise<T> result = Promise.promise();
        long timerId = vertx.setTimer(LOCK_WAIT_MILLIS, ignored -> result.tryFail(new AssertionError(message)));
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
