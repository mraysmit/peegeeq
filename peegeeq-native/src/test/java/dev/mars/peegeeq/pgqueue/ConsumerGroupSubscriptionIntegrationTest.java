package dev.mars.peegeeq.pgqueue;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStateException;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.ALL;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for consumer group subscription lifecycle paths.
 *
 * <p>Covers the following uncovered paths identified in coverage analysis:</p>
 * <ul>
 *   <li>{@code start(SubscriptionOptions)} with real {@code databaseService} (L346-373)</li>
 *   <li>{@code stopGracefully()} with {@code startedWithSubscription=true}: stores STOPPED</li>
 *   <li>{@code isOffsetWatermarkTopic} returning false for unknown topic (L356)</li>
 *   <li>{@code isOffsetWatermarkTopic} returning false for PUB_SUB topic (L360-361)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-13
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerGroupSubscriptionIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupSubscriptionIntegrationTest.class);


    private static final String SERVICE_ID = "sub-test";

    @Container
    static final PostgreSQLContainer postgres =
            PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;
    private PgConnectionManager connectionManager;
    private final List<String> testTopics = new ArrayList<>();

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, ALL);
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            databaseService = new PgDatabaseService(manager);
            adapter = new VertxPoolAdapter(
                    databaseService.getVertx(),
                    databaseService.getPool(),
                    databaseService
            );

            mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            connectionManager = new PgConnectionManager(databaseService.getVertx(), null);
            PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                    .host(postgres.getHost())
                    .port(postgres.getFirstMappedPort())
                    .database(postgres.getDatabaseName())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                    .build();
            PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                    .maxSize(10)
                    .shared(false)
                    .build();
            connectionManager.getOrCreateReactivePool(SERVICE_ID, connConfig, poolConfig);
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");

        Future<Void> teardown = Future.succeededFuture();
        if (connectionManager != null) {
            teardown = cleanupTestData()
                    .onFailure(err -> logger.error("Failed to clean subscription test data", err))
                    .eventually(connectionManager::close);
        }
        teardown
                .onSuccess(v -> finishManagerClose(testContext, null))
                .onFailure(err -> finishManagerClose(testContext, err));
    }

    private void finishManagerClose(VertxTestContext testContext, Throwable teardownFailure) {
        if (manager == null) {
            if (teardownFailure == null) {
                testContext.completeNow();
            } else {
                testContext.failNow(teardownFailure);
            }
            return;
        }

        manager.closeReactive()
                .onSuccess(v -> {
                    if (teardownFailure == null) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(teardownFailure);
                    }
                })
                .onFailure(closeFailure -> {
                    if (teardownFailure == null) {
                        testContext.failNow(closeFailure);
                    } else {
                        teardownFailure.addSuppressed(closeFailure);
                        testContext.failNow(teardownFailure);
                    }
                });
    }

    // ========================================================================
    // start(SubscriptionOptions) with real databaseService
    // ========================================================================

    @Test
    @DisplayName("start(SubscriptionOptions) creates subscription and starts group")
    void startWithSubscriptionOptions(VertxTestContext testContext) {
        logger.info("Test: start with subscription options");
        String topic = "test-sub-start-" + System.nanoTime();
        String groupName = "sub-1";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, manager.getConfiguration(), databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.defaults())
                            .compose(started -> {
                                assertTrue(group.isActive(),
                                        "Group should be ACTIVE after start(SubscriptionOptions)");
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
                                return querySubscriptionStatus(topic, groupName);
                            })
                            .map(status -> {
                                assertEquals("ACTIVE", status,
                                        "Subscription should be persisted as ACTIVE after startup");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // stopGracefully() with startedWithSubscription=true
    // ========================================================================

    @Test
    @DisplayName("stopGracefully stores STOPPED after start(SubscriptionOptions) and a start makes it ACTIVE")
    void stopGracefullyStoresStoppedAndStartReactivates(VertxTestContext testContext) {
        logger.info("Test: stop gracefully stores STOPPED and a start reactivates");
        String topic = "test-sub-stop-" + System.nanoTime();
        String groupName = "sub-2";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, manager.getConfiguration(), databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.defaults())
                            .compose(started -> {
                                assertTrue(group.isActive(), "Group should be ACTIVE before stopGracefully");
                                return group.stopGracefully();
                            })
                            .map(stopped -> {
                                assertFalse(group.isActive(),
                                        "Group should not be active after stopGracefully");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "Group should be NEW after stop");
                                return stopped;
                            })
                            .compose(stopped -> querySubscriptionStatus(topic, groupName))
                            .compose(status -> {
                                assertEquals("STOPPED", status,
                                        "stopGracefully should persist the stopped subscription state");
                                return group.start(SubscriptionOptions.defaults());
                            })
                            .compose(restarted -> querySubscriptionStatus(topic, groupName))
                            .map(status -> {
                                assertEquals("ACTIVE", status,
                                        "A start should make the stopped subscription ACTIVE");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // A restart uses the stored start bound
    // ========================================================================

    @Test
    @DisplayName("a restart with FROM_NOW keeps the stored start bound and delivers a message sent while the group was stopped")
    void restartAfterGracefulStopDeliversMessageSentWhileStopped(VertxTestContext testContext) {
        String topic = "test-sub-restart-" + System.nanoTime();
        String groupName = "sub-restart";
        Promise<String> received = Promise.promise();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, manager.getConfiguration(), databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> {
                        received.tryComplete(msg.getPayload());
                        return Future.succeededFuture();
                    });
                    PgNativeQueueProducer<String> producer = new PgNativeQueueProducer<>(
                            adapter, mapper, topic, String.class, null, manager.getConfiguration());

                    // Both starts name FROM_NOW. The first start creates the start bound. The
                    // second start must use the stored bound, not a new "now".
                    return group.start(SubscriptionOptions.defaults())
                            .compose(started -> group.stopGracefully())
                            .compose(stopped -> producer.send("sent-while-stopped"))
                            .compose(sent -> group.start(SubscriptionOptions.defaults()))
                            .compose(restarted -> withTimeout(received.future(), 10_000,
                                    "The message sent while the group was stopped was not delivered after the restart"))
                            .map(payload -> {
                                assertEquals("sent-while-stopped", payload);
                                return (Void) null;
                            })
                            .eventually(() -> {
                                producer.close();
                                return group.close();
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // The subscription of a native group is bound to queue_messages
    // ========================================================================

    @Test
    @DisplayName("a start with FROM_NOW stores the next id of the topic in queue_messages")
    void startWithFromNowStoresTheNextQueueMessageId(VertxTestContext testContext) {
        String topic = "test-sub-native-store-" + System.nanoTime();
        String groupName = "sub-native-store";

        // The topic has rows in both message tables, and its next id differs between them.
        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> insertMessages("outbox", topic, 2))
                .compose(v -> insertMessages("queue_messages", topic, 5))
                .compose(v -> Future.all(nextMessageId("outbox", topic), nextMessageId("queue_messages", topic)))
                .compose(nextIds -> {
                    long nextOutboxId = nextIds.resultAt(0);
                    long nextQueueMessageId = nextIds.resultAt(1);
                    assertNotEquals(nextOutboxId, nextQueueMessageId,
                            "The test needs different next ids in the two message tables");
                    AtomicReference<Long> consumerStartBound = new AtomicReference<>();
                    PgNativeConsumerGroup<String> group =
                            groupRecordingConsumerStartBound(groupName, topic, consumerStartBound);

                    return group.start(SubscriptionOptions.defaults())
                            .compose(started -> databaseService.getSubscriptionService()
                                    .getSubscription(topic, groupName))
                            .map(subscription -> {
                                assertEquals(nextQueueMessageId, subscription.startFromMessageId(),
                                        "FROM_NOW must resolve to the next id of the topic in queue_messages: outbox="
                                                + nextOutboxId + ", queue_messages=" + nextQueueMessageId);
                                assertEquals(nextQueueMessageId, consumerStartBound.get(),
                                        "The consumer must start from the stored start bound");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("a start with FROM_NOW on an existing subscription starts from the stored bound")
    void startOnExistingSubscriptionStartsFromTheStoredBound(VertxTestContext testContext) {
        String topic = "test-sub-existing-bound-" + System.nanoTime();
        String groupName = "sub-existing-bound";
        AtomicReference<Long> consumerStartBound = new AtomicReference<>();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> databaseService.getSubscriptionService().subscribe(topic, groupName,
                        SubscriptionStore.NATIVE,
                        SubscriptionOptions.builder()
                                .startPosition(StartPosition.FROM_MESSAGE_ID)
                                .startFromMessageId(5L)
                                .build()))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group =
                            groupRecordingConsumerStartBound(groupName, topic, consumerStartBound);

                    return group.start(SubscriptionOptions.defaults())
                            .map(started -> {
                                assertTrue(group.isActive(), "The group must be active after the start");
                                assertEquals(5L, consumerStartBound.get(),
                                        "The consumer must start from the stored start bound");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("a start subscribes with the native store, and it fails when the subscribe fails")
    void startFailsWhenTheSubscribeFails(VertxTestContext testContext) {
        String topic = "test-sub-subscribe-fails-" + System.nanoTime();
        String groupName = "sub-subscribe-fails";
        IllegalStateException subscribeFailure = new IllegalStateException("subscribe failed");
        AtomicReference<SubscriptionStore> subscribedStore = new AtomicReference<>();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group =
                            groupWithFailingSubscribe(groupName, topic, subscribeFailure, subscribedStore);

                    return group.start(SubscriptionOptions.defaults())
                            .transform(result -> {
                                if (result.succeeded()) {
                                    return Future.<SubscriptionInfo>failedFuture(new AssertionError(
                                            "A start must fail when the subscribe fails"));
                                }
                                assertSame(subscribeFailure, result.cause(),
                                        "The subscribe failure must be returned unchanged");
                                assertEquals(SubscriptionStore.NATIVE, subscribedStore.get(),
                                        "A native group must subscribe with the native store");
                                assertFalse(group.isActive(),
                                        "The group must not be active after a failed start");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "A failed start must leave the group in NEW");
                                return databaseService.getSubscriptionService().getSubscription(topic, groupName);
                            })
                            .map(subscription -> {
                                assertNull(subscription, "A failed start must create no subscription");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("a start fails on a cancelled subscription and leaves the group inactive")
    void startFailsOnACancelledSubscription(VertxTestContext testContext) {
        String topic = "test-sub-cancelled-" + System.nanoTime();
        String groupName = "sub-cancelled";
        SubscriptionService subscriptionService = databaseService.getSubscriptionService();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> subscriptionService.subscribe(topic, groupName, SubscriptionStore.NATIVE,
                        SubscriptionOptions.defaults()))
                .compose(v -> subscriptionService.cancel(topic, groupName))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, manager.getConfiguration(), databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.defaults())
                            .transform(result -> {
                                if (result.succeeded()) {
                                    return Future.<Void>failedFuture(new AssertionError(
                                            "A start must fail on a cancelled subscription"));
                                }
                                SubscriptionStateException refusal =
                                        assertInstanceOf(SubscriptionStateException.class, result.cause());
                                assertEquals(SubscriptionState.CANCELLED, refusal.getCurrentState());
                                assertFalse(group.isActive(),
                                        "The group must not be active after a failed start");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "A failed start must leave the group in NEW");
                                return Future.<Void>succeededFuture();
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // A start fails when the stored start bound cannot be read
    // ========================================================================

    @Test
    @DisplayName("a start fails and the group stays inactive when the read of the stored start bound fails")
    void startFailsWhenTheStoredBoundCannotBeRead(VertxTestContext testContext) {
        String topic = "test-sub-bound-read-fails-" + System.nanoTime();
        String groupName = "sub-bound-read-fails";
        IllegalStateException readFailure = new IllegalStateException("stored start bound read failed");

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = groupWithSubscriptionRead(
                            groupName, topic, Future.failedFuture(readFailure));

                    return group.start(SubscriptionOptions.defaults())
                            .transform(result -> {
                                if (result.succeeded()) {
                                    return Future.<SubscriptionInfo>failedFuture(new AssertionError(
                                            "A start must fail when the stored start bound cannot be read"));
                                }
                                assertSame(readFailure, result.cause(),
                                        "The read failure must be returned unchanged");
                                assertFalse(group.isActive(),
                                        "The group must not be active after a failed start");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "A failed start must leave the group in NEW");
                                return databaseService.getSubscriptionService().getSubscription(topic, groupName);
                            })
                            .map(subscription -> {
                                assertNotNull(subscription,
                                        "The subscribe ran before the read of the stored start bound");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("a start fails and the group stays inactive when the subscription is missing after the subscribe")
    void startFailsWhenTheSubscriptionIsMissingAfterSubscribe(VertxTestContext testContext) {
        String topic = "test-sub-bound-missing-" + System.nanoTime();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = groupWithSubscriptionRead(
                            "sub-bound-missing", topic, Future.succeededFuture(null));

                    return group.start(SubscriptionOptions.defaults())
                            .transform(result -> {
                                if (result.succeeded()) {
                                    return Future.<Void>failedFuture(new AssertionError(
                                            "A start must fail when the subscription is missing after the subscribe"));
                                }
                                assertInstanceOf(IllegalStateException.class, result.cause());
                                assertTrue(result.cause().getMessage().contains("Subscription not found after subscribe"),
                                        "The failure must name the missing subscription; got: " + result.cause());
                                assertFalse(group.isActive(),
                                        "The group must not be active after a failed start");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "A failed start must leave the group in NEW");
                                return Future.<Void>succeededFuture();
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // isOffsetWatermarkTopic returns false for unknown topic
    // ========================================================================

    @Test
    @DisplayName("start() falls back to reference counting for unknown topic")
    void startFallsBackForUnknownTopic(VertxTestContext testContext) {
        logger.info("Test: start falls back for unknown topic");
        String topic = "test-sub-unknown-" + System.nanoTime();
        String groupName = "sub-3";

        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, manager.getConfiguration(), null,
                connectionManager, SERVICE_ID
        );
        group.setMessageHandler(msg -> Future.succeededFuture());

        group.start()
                .map(v -> {
                    assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                            "Group should be ACTIVE via reference counting fallback");
                    return (Void) null;
                })
                .eventually(() -> group.close())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // isOffsetWatermarkTopic returns false for PUB_SUB topic
    // ========================================================================

    @Test
    @DisplayName("start() uses reference counting for PUB_SUB topic")
    void startUsesReferenceCountingForPubSubTopic(VertxTestContext testContext) {
        logger.info("Test: start uses reference counting for pub sub topic");
        String topic = "test-sub-pubsub-" + System.nanoTime();
        String groupName = "sub-4";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, manager.getConfiguration(), null,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start()
                            .map(started -> {
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                        "Group should be ACTIVE in reference counting mode for PUB_SUB topic");
                                return (Void) null;
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Unknown topic metadata defaults to reference counting while durable subscription persists
    // ========================================================================

    @Test
    @DisplayName("start(SubscriptionOptions) persists an unknown-topic subscription and uses reference counting")
    void startWithSubscriptionOnMissingTopicUsesReferenceCounting(VertxTestContext testContext) {
        logger.info("Test: start with subscription on missing topic metadata");
        String topic = "test-sub-recover-" + System.nanoTime();
        String groupName = "sub-5";
        testTopics.add(topic);

        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, manager.getConfiguration(), databaseService,
                connectionManager, SERVICE_ID
        );
        group.setMessageHandler(msg -> Future.succeededFuture());

        group.start(SubscriptionOptions.defaults())
                .compose(started -> {
                    assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                            "Unknown topic metadata should default to reference counting");
                    return Future.all(
                            querySubscriptionStatus(topic, groupName),
                            queryTopicCount(topic));
                })
                .map(results -> {
                    assertEquals("ACTIVE", results.resultAt(0),
                            "The durable subscription should be persisted");
                    assertEquals(0L, results.<Long>resultAt(1),
                            "The test must exercise absent topic metadata");
                    return (Void) null;
                })
                .eventually(() -> group.close())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        testTopics.add(topic);
        return connectionManager.withTransaction(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                                "VALUES ($1, 'PUB_SUB', $2) ON CONFLICT (topic) DO NOTHING"
                ).execute(Tuple.of(topic, completionTrackingMode))
                        .map(rows -> (Void) null)
        );
    }

    /**
     * Builds a group on the real subscription service, except for its read of the
     * subscription. The start reads the subscription once, after the subscribe.
     */
    private PgNativeConsumerGroup<String> groupWithSubscriptionRead(String groupName, String topic,
                                                                     Future<SubscriptionInfo> readAfterSubscribe) {
        SubscriptionManager subscriptionService = new SubscriptionManager(connectionManager, SERVICE_ID) {
            @Override
            public Future<SubscriptionInfo> getSubscription(String readTopic, String readGroupName) {
                return readAfterSubscribe;
            }
        };
        return groupOn(groupName, topic, subscriptionService);
    }

    /**
     * Builds a group whose subscribe fails. The store that the group names is recorded.
     */
    private PgNativeConsumerGroup<String> groupWithFailingSubscribe(String groupName, String topic,
                                                                     RuntimeException subscribeFailure,
                                                                     AtomicReference<SubscriptionStore> subscribedStore) {
        SubscriptionManager subscriptionService = new SubscriptionManager(connectionManager, SERVICE_ID) {
            @Override
            public Future<Void> subscribe(String subscribeTopic, String subscribeGroupName,
                                          SubscriptionStore store, SubscriptionOptions options) {
                subscribedStore.set(store);
                return Future.failedFuture(subscribeFailure);
            }
        };
        return groupOn(groupName, topic, subscriptionService);
    }

    private PgNativeConsumerGroup<String> groupOn(String groupName, String topic,
                                                  SubscriptionService subscriptionService) {
        PgDatabaseService serviceWithSubscriptionService = new PgDatabaseService(manager) {
            @Override
            public SubscriptionService getSubscriptionService() {
                return subscriptionService;
            }
        };
        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, manager.getConfiguration(), serviceWithSubscriptionService,
                connectionManager, SERVICE_ID
        );
        group.setMessageHandler(msg -> Future.succeededFuture());
        return group;
    }

    /**
     * Builds a group that records the start bound that it gives its consumer.
     */
    private PgNativeConsumerGroup<String> groupRecordingConsumerStartBound(String groupName, String topic,
                                                                            AtomicReference<Long> consumerStartBound) {
        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, manager.getConfiguration(), databaseService,
                connectionManager, SERVICE_ID,
                (minimumMessageId, minimumCreatedAt) -> {
                    consumerStartBound.set(minimumMessageId);
                    return new PgNativeQueueConsumer<>(adapter, mapper, topic, String.class,
                            NoOpMetricsProvider.INSTANCE, manager.getConfiguration(),
                            minimumMessageId, minimumCreatedAt);
                });
        group.setMessageHandler(msg -> Future.succeededFuture());
        return group;
    }

    private Future<Void> insertMessages(String table, String topic, int count) {
        String sql = "INSERT INTO " + table + " (topic, payload) "
                + "SELECT $1::varchar, '{}'::jsonb FROM generate_series(1, $2::int)";
        return connectionManager.withTransaction(SERVICE_ID, conn ->
                conn.preparedQuery(sql).execute(Tuple.of(topic, count)).map(rows -> (Void) null));
    }

    private Future<Long> nextMessageId(String table, String topic) {
        String sql = "SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM " + table + " WHERE topic = $1";
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(sql).execute(Tuple.of(topic))
                        .map(rows -> rows.iterator().next().getLong("next_id")));
    }

    private <T> Future<T> withTimeout(Future<T> future, long timeoutMillis, String message) {
        Promise<T> result = Promise.promise();
        long timerId = databaseService.getVertx().setTimer(timeoutMillis,
                ignored -> result.tryFail(new AssertionError(message)));
        future
                .onSuccess(value -> {
                    databaseService.getVertx().cancelTimer(timerId);
                    result.tryComplete(value);
                })
                .onFailure(error -> {
                    databaseService.getVertx().cancelTimer(timerId);
                    result.tryFail(error);
                });
        return result.future();
    }

    private Future<String> querySubscriptionStatus(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "SELECT subscription_status FROM outbox_topic_subscriptions " +
                                "WHERE topic = $1 AND group_name = $2")
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> rows.iterator().next().getString("subscription_status")));
    }

    private Future<Long> queryTopicCount(String topic) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery("SELECT COUNT(*) AS count FROM outbox_topics WHERE topic = $1")
                        .execute(Tuple.of(topic))
                        .map(rows -> rows.iterator().next().getLong("count")));
    }

    private Future<Void> cleanupTestData() {
        if (testTopics.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] topics = testTopics.toArray(new String[0]);
        return connectionManager.withTransaction(SERVICE_ID, conn ->
                conn.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic = ANY($1::text[])").execute(Tuple.of(topics))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_partition_offsets WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topic_watermarks WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topic_subscriptions WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM queue_messages WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topics WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .map(rows -> (Void) null)
        );
    }
}
