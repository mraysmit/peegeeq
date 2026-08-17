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
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
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
 *   <li>{@code stopGracefully()} with {@code startedWithSubscription=true} (L401-410)</li>
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
    @DisplayName("stopGracefully cancels subscription after start(SubscriptionOptions)")
    void stopGracefullyCancelsSubscription(VertxTestContext testContext) {
        logger.info("Test: stop gracefully cancels subscription");
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
                            .map(status -> {
                                assertEquals("CANCELLED", status,
                                        "stopGracefully should persist the cancelled subscription state");
                                return (Void) null;
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
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topics WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .map(rows -> (Void) null)
        );
    }
}
