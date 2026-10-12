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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for {@link OutboxConsumerGroup#stopGracefully()} against a real PostgreSQL container.
 *
 * <p>Validates that graceful shutdown stores the status STOPPED for the subscription
 * when the group was started with subscription options, runs no cleanup, and is a
 * no-op when the group was started without subscription options or is not active.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("OutboxConsumerGroup \u2014 graceful shutdown")
class OutboxConsumerGroupGracefulShutdownTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupGracefulShutdownTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private OutboxConsumerGroup<String> group;
    private Vertx vertx;
    private PeeGeeQManager manager;
    private DatabaseService databaseService;
    private PeeGeeQConfiguration config;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        this.config = new PeeGeeQConfiguration("default", testProps);
        this.manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        this.manager.start()
                .onSuccess(v -> {
                    this.databaseService = new PgDatabaseService(manager);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future.<Void>succeededFuture()
                .eventually(() -> group != null ? group.close() : Future.succeededFuture())
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // No-op cases
    // =========================================================================

    @Test
    @Timeout(5)
    @DisplayName("stopGracefully on NEW group returns succeeded future")
    void stopGracefully_whenNotActive_returnsSuccess() {
        group = createGroup("not-active-group", "test-topic");
        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed on non-active group");
    }

    @Test
    @DisplayName("stopGracefully on CLOSED group returns succeeded future")
    void stopGracefully_whenClosed_returnsSuccess(VertxTestContext testContext) throws Exception {
        group = createGroup("closed-group", "test-topic");
        group.close()
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    var future = group.stopGracefully();
                    assertTrue(future.succeeded(), "Should succeed on closed group");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully is idempotent second call returns succeeded")
    void stopGracefully_idempotent(VertxTestContext testContext) throws Exception {
        group = createGroup("idempotent-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start()
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped after first call");
                    var second = group.stopGracefully();
                    assertTrue(second.succeeded(), "Second stopGracefully should succeed (idempotent)");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Without subscription local stop only
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on group started without subscription stops locally, no status update")
    void stopGracefully_withoutSubscription_stopsLocallyOnly(VertxTestContext testContext) throws Exception {
        group = createGroup("local-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start()
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped");
                    // Group was started without SubscriptionOptions: stopGracefully completes without a status update
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // With subscription: store STOPPED, then stop
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on subscription-started group stores STOPPED then stops")
    void stopGracefully_withSubscription_storesStoppedAndStops(VertxTestContext testContext) throws Exception {
        group = createGroup("sub-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        // Start with subscription options: creates the subscription in the DB
        group.start(SubscriptionOptions.builder().build())
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .compose(v -> subscriptionState("test-topic", "sub-group"))
                .onComplete(testContext.succeeding(state -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped");
                    assertEquals(SubscriptionState.STOPPED, state,
                            "stopGracefully must store STOPPED for the subscription");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("start after stopGracefully makes the stopped subscription ACTIVE")
    void start_afterStopGracefully_makesSubscriptionActive(VertxTestContext testContext) throws Exception {
        group = createGroup("restart-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        SubscriptionOptions options = SubscriptionOptions.builder().build();
        group.start(options)
                .compose(v -> group.stopGracefully())
                .compose(v -> subscriptionState("test-topic", "restart-group"))
                .compose(state -> {
                    assertEquals(SubscriptionState.STOPPED, state,
                            "Pre-condition: the subscription must be STOPPED before the restart");
                    return group.start(options);
                })
                .compose(v -> subscriptionState("test-topic", "restart-group"))
                .onComplete(testContext.succeeding(state -> testContext.verify(() -> {
                    assertTrue(group.isActive(), "Group should be active after the restart");
                    assertEquals(SubscriptionState.ACTIVE, state,
                            "A start must make the stopped subscription ACTIVE");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("start after stopGracefully delivers a message sent while the group was stopped")
    void start_afterStopGracefully_deliversMessageSentWhileStopped(VertxTestContext testContext) throws Exception {
        String topic = "restart-delivery-topic-" + UUID.randomUUID().toString().substring(0, 8);
        group = createGroup("restart-delivery-group", topic);
        Promise<String> received = Promise.promise();
        group.addConsumer("c1", msg -> {
            received.tryComplete(msg.getPayload());
            return Future.succeededFuture();
        });
        OutboxFactory producerFactory = new OutboxFactory(databaseService, config);
        MessageProducer<String> producer = producerFactory.createProducer(topic, String.class);
        // Both starts name FROM_NOW. The first start creates the start bound. The second start
        // must keep it, so the message sent while the group was stopped is inside the bound.
        SubscriptionOptions options = SubscriptionOptions.builder().build();
        group.start(options)
                .compose(v -> group.stopGracefully())
                .compose(v -> producer.send("sent-while-stopped"))
                .compose(v -> group.start(options))
                .compose(v -> received.future())
                // The producer factory is closed whatever the result. The scenario failure comes
                // first; a failed close fails the test when the scenario passed.
                .transform(result -> producerFactory.close().transform(closed -> {
                    if (result.failed()) {
                        return Future.<String>failedFuture(result.cause());
                    }
                    if (closed.failed()) {
                        return Future.<String>failedFuture(closed.cause());
                    }
                    return Future.succeededFuture(result.result());
                }))
                .onComplete(testContext.succeeding(payload -> testContext.verify(() -> {
                    assertEquals("sent-while-stopped", payload);
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "The message sent while the group was stopped was not delivered after the restart");
    }

    @Test
    @DisplayName("stopGracefully leaves a pending message of the topic PENDING")
    void stopGracefully_leavesPendingMessagePending(VertxTestContext testContext) throws Exception {
        String topic = "pending-topic-" + UUID.randomUUID().toString().substring(0, 8);
        group = createGroup("pending-group", topic);
        group.addConsumer("c1", msg -> Future.succeededFuture());
        AtomicLong messageId = new AtomicLong();
        // The start position is above every message id, so the running group never claims the message.
        group.start(SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_MESSAGE_ID)
                        .startFromMessageId(Long.MAX_VALUE)
                        .build())
                .compose(v -> insertPendingMessageAfterSubscription(topic, "pending-group"))
                .compose(id -> {
                    messageId.set(id);
                    return group.stopGracefully();
                })
                .compose(v -> messageStatus(messageId.get()))
                .onComplete(testContext.succeeding(status -> testContext.verify(() -> {
                    assertEquals("PENDING", status,
                            "A graceful stop must not change a pending message of the topic");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully stops the group and returns the failure when the status update fails")
    void stopGracefully_statusUpdateFails_stopsAndReturnsFailure(VertxTestContext testContext) throws Exception {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Failed to stop subscription') is EXPECTED: this test deletes the subscription row so the status update of stopGracefully fails");
        group = createGroup("status-update-fails-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start(SubscriptionOptions.builder().build())
                // Without its row the status update fails with "Subscription not found".
                .compose(v -> deleteSubscription("test-topic", "status-update-fails-group"))
                .compose(v -> group.stopGracefully())
                .onSuccess(v -> testContext.failNow("stopGracefully must return the failed status update"))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("Subscription not found"),
                            "The failure of the status update must be returned; got: " + err.getMessage());
                    assertFalse(group.isActive(), "Group must be stopped when the status update fails");
                    testContext.completeNow();
                }));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully after stop() is a no-op and the subscription stays ACTIVE")
    void stopGracefully_afterStop_isNoOp(VertxTestContext testContext) throws Exception {
        group = createGroup("stop-then-graceful", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        // Start with subscription options, then stop (not gracefully), then verify stopGracefully is no-op
        group.start(SubscriptionOptions.builder().build())
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stop())  // regular stop: instance-local, no status update
                .compose(v -> {
                    assertFalse(group.isActive());
                    var future = group.stopGracefully();
                    assertTrue(future.succeeded(), "Should succeed as no-op: group is already stopped");
                    return subscriptionState("test-topic", "stop-then-graceful");
                })
                .onComplete(testContext.succeeding(state -> testContext.verify(() -> {
                    assertEquals(SubscriptionState.ACTIVE, state,
                            "stop() and a no-op stopGracefully must leave the subscription status unchanged");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxConsumerGroup<String> createGroup(String groupName, String topic) {
        return new OutboxConsumerGroup<>(
                groupName, topic, String.class,
                databaseService, null, null, config);
    }

    private Future<SubscriptionState> subscriptionState(String topic, String groupName) {
        return databaseService.getSubscriptionService()
                .getSubscription(topic, groupName)
                .map(SubscriptionInfo::state);
    }

    /**
     * Inserts a PENDING message whose creation time is one second after the group subscribed.
     * The creation time is derived from the stored subscription time, so the test does not
     * depend on the clocks of the JVM and the database being equal.
     */
    private Future<Long> insertPendingMessageAfterSubscription(String topic, String groupName) {
        return manager.getPool().withTransaction(connection -> connection
                .preparedQuery("""
                    INSERT INTO outbox (topic, payload, status, created_at)
                    SELECT s.topic, $1::jsonb, 'PENDING', s.subscribed_at + INTERVAL '1 second'
                    FROM outbox_topic_subscriptions s
                    WHERE s.topic = $2 AND s.group_name = $3
                    RETURNING id
                    """)
                .execute(Tuple.of("{\"value\": \"pending-after-subscription\"}", topic, groupName))
                .map(rows -> rows.iterator().next().getLong("id")));
    }

    private Future<String> messageStatus(long messageId) {
        return manager.getPool()
                .preparedQuery("SELECT status FROM outbox WHERE id = $1")
                .execute(Tuple.of(messageId))
                .map(rows -> rows.iterator().next().getString("status"));
    }

    private Future<Void> deleteSubscription(String topic, String groupName) {
        return manager.getPool().withTransaction(connection -> connection
                .preparedQuery("DELETE FROM outbox_topic_subscriptions WHERE topic = $1 AND group_name = $2")
                .execute(Tuple.of(topic, groupName))
                .mapEmpty());
    }
}
