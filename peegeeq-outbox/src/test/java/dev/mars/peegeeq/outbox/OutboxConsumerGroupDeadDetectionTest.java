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
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
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
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dead consumer detection must never mark a message processed that no consumer has processed.
 *
 * <p>A running in-process group sends no subscription heartbeat yet, so detection can mark its
 * subscription DEAD while the group runs. Each test starts a group the way an application does,
 * through the outbox factory and {@code start(SubscriptionOptions)}, leaves one message unread or
 * in flight, expires the heartbeat, and runs one detection pass with the production
 * collaborators. The message must still be PENDING afterwards, with its required group count
 * unchanged. Proposal test R-1 (delivery outcome redesign, phase 1, increment 1d).</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Dead consumer detection leaves the messages of a running group unchanged")
class OutboxConsumerGroupDeadDetectionTest {

    private static final String POOL = PeeGeeQDefaults.DEFAULT_POOL_ID;

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
    void tearDown(VertxTestContext testContext) {
        TestCleanup.run(
                        () -> factory != null ? factory.close() : Future.succeededFuture(),
                        () -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("R-1, QUEUE topic: a detection run that marks a running group DEAD leaves its unread message PENDING")
    void queueTopic_detectionRunLeavesUnreadMessagePending(VertxTestContext testContext) throws Exception {
        String topic = "r1-queue-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "r1-queue-group";
        AtomicInteger deliveries = new AtomicInteger();
        AtomicLong messageId = new AtomicLong();

        ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);
        group.setMessageHandler(msg -> {
            deliveries.incrementAndGet();
            return Future.succeededFuture();
        });

        // The start bound lies above every id, so the running group never claims the message.
        group.start(expiringOptions()
                        .startPosition(StartPosition.FROM_MESSAGE_ID)
                        .startFromMessageId(Long.MAX_VALUE)
                        .build())
                .compose(started -> insertMessageAfterSubscription(topic, groupName, null))
                .compose(id -> {
                    messageId.set(id);
                    return expireHeartbeat(topic, groupName);
                })
                .compose(expired -> detectionJob().runDetectionOnceWithDetails())
                .compose(details -> subscriptionState(topic, groupName))
                .compose(state -> messageRow(messageId.get()).map(row -> {
                    testContext.verify(() -> {
                        assertEquals(SubscriptionState.DEAD, state,
                                "precondition: the detection run marked the running group DEAD");
                        assertEquals(0, deliveries.get(), "the group never received the message");
                        assertEquals("PENDING", row.getString("status"),
                                "a detection run must not mark an unread message processed");
                        assertEquals(1, row.getInteger("required_consumer_groups"),
                                "a detection run must not change the required group count");
                    });
                    return (Void) null;
                }))
                .transform(TestCleanup.cleanup(group::stopGracefully))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    @DisplayName("R-1, OFFSET_WATERMARK topic: a detection run that marks a running group DEAD leaves its in-flight message PENDING")
    void offsetWatermarkTopic_detectionRunLeavesInFlightMessagePending(VertxTestContext testContext)
            throws Exception {
        String topic = "r1-owm-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "r1-owm-group";
        SubscriptionOptions options = expiringOptions().build();
        Promise<Void> delivered = Promise.promise();
        // The handler holds the message until the assertions have run, so the group commits no
        // offset and no watermark sweep can complete the row.
        Promise<Void> release = Promise.promise();
        AtomicLong messageId = new AtomicLong();

        ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);
        group.setMessageHandler(msg -> {
            delivered.tryComplete();
            return release.future();
        });

        // The subscription exists before the message, and the message exists before the group
        // joins, so the engine is assigned the message's key when it starts.
        configureOffsetWatermarkTopic(topic)
                .compose(v -> databaseService.getSubscriptionService()
                        .subscribe(topic, groupName, SubscriptionStore.OUTBOX, options))
                .compose(v -> insertMessageAfterSubscription(topic, groupName, "r1-key"))
                .compose(id -> {
                    messageId.set(id);
                    return group.start(options);
                })
                .compose(started -> delivered.future())
                .compose(inFlight -> expireHeartbeat(topic, groupName))
                .compose(expired -> detectionJob().runDetectionOnceWithDetails())
                .compose(details -> subscriptionState(topic, groupName))
                .compose(state -> messageRow(messageId.get()).map(row -> {
                    testContext.verify(() -> {
                        assertEquals(SubscriptionState.DEAD, state,
                                "precondition: the detection run marked the running group DEAD");
                        assertEquals("PENDING", row.getString("status"),
                                "a detection run must not mark an in-flight message processed");
                        assertEquals(1, row.getInteger("required_consumer_groups"),
                                "a detection run must not change the required group count");
                    });
                    return (Void) null;
                }))
                .transform(TestCleanup.cleanup(() -> {
                    release.tryComplete();
                    return group.stopGracefully();
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    /** A one-second heartbeat that one missed detection run turns DEAD. */
    private static SubscriptionOptions.Builder expiringOptions() {
        return SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .deadAfterMisses(1);
    }

    /** The detection job with the collaborators that {@code PeeGeeQManager} gives it. */
    private DeadConsumerDetectionJob detectionJob() {
        PgConnectionManager connectionManager = databaseService.getClientFactory().getConnectionManager();
        return new DeadConsumerDetectionJob(manager.getVertx(), new DeadConsumerDetector(connectionManager, POOL));
    }

    private Future<Void> configureOffsetWatermarkTopic(String topic) {
        return databaseService.getPool().withTransaction(conn -> conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) "
                        + "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK')")
                .execute(Tuple.of(topic))
                .mapEmpty());
    }

    /**
     * Inserts a PENDING message dated one second after the group subscribed, on the database
     * clock, so that the message belongs to the group's subscription.
     */
    private Future<Long> insertMessageAfterSubscription(String topic, String groupName, String messageGroup) {
        return databaseService.getPool().withTransaction(conn -> conn.preparedQuery("""
                        INSERT INTO outbox (topic, payload, status, message_group, created_at)
                        SELECT s.topic, $1, 'PENDING', $2, s.subscribed_at + INTERVAL '1 second'
                        FROM outbox_topic_subscriptions s
                        WHERE s.topic = $3 AND s.group_name = $4
                        RETURNING id
                        """)
                .execute(Tuple.of(new JsonObject().put("data", "r1"), messageGroup, topic, groupName))
                .map(rows -> rows.iterator().next().getLong("id")));
    }

    /** Moves the last heartbeat ten seconds into the past, beyond the two-second timeout. */
    private Future<Void> expireHeartbeat(String topic, String groupName) {
        return databaseService.getPool().withTransaction(conn -> conn.preparedQuery(
                        "UPDATE outbox_topic_subscriptions SET last_heartbeat_at = NOW() - INTERVAL '10 seconds' "
                        + "WHERE topic = $1 AND group_name = $2")
                .execute(Tuple.of(topic, groupName))
                .compose(rows -> rows.rowCount() == 1
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError(
                                "expected one subscription row, updated " + rows.rowCount()))));
    }

    private Future<SubscriptionState> subscriptionState(String topic, String groupName) {
        return databaseService.getSubscriptionService().getSubscription(topic, groupName)
                .map(info -> info.state());
    }

    private Future<Row> messageRow(long id) {
        return databaseService.getPool().preparedQuery(
                        "SELECT status, required_consumer_groups FROM outbox WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.iterator().next());
    }
}
