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
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
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
import java.util.concurrent.TimeUnit;

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

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled"
    };

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, ALL);
    }

    @BeforeEach
    void setUp() {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

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
                .schema("public")
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool(SERVICE_ID, connConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (connectionManager != null) {
            cleanupTestData().transform(ar -> Future.<Void>succeededFuture()).await();
            connectionManager.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    // ========================================================================
    // start(SubscriptionOptions) with real databaseService
    // ========================================================================

    @Test
    @DisplayName("start(SubscriptionOptions) creates subscription and starts group")
    void startWithSubscriptionOptions(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with subscription options");
        String topic = "test-sub-start-" + System.nanoTime();
        String groupName = "sub-1";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.defaults())
                            .map(started -> {
                                assertTrue(group.isActive(),
                                        "Group should be ACTIVE after start(SubscriptionOptions)");
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
                                return (Void) null;
                            })
                            .eventually(() -> { group.close(); return Future.succeededFuture(); });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // stopGracefully() with startedWithSubscription=true
    // ========================================================================

    @Test
    @DisplayName("stopGracefully cancels subscription after start(SubscriptionOptions)")
    void stopGracefullyCancelsSubscription(VertxTestContext testContext) throws Exception {
        logger.info("Test: stop gracefully cancels subscription");
        String topic = "test-sub-stop-" + System.nanoTime();
        String groupName = "sub-2";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
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
                                return (Void) null;
                            })
                            .eventually(() -> { group.close(); return Future.succeededFuture(); });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // isOffsetWatermarkTopic returns false for unknown topic
    // ========================================================================

    @Test
    @DisplayName("start() falls back to reference counting for unknown topic")
    void startFallsBackForUnknownTopic(VertxTestContext testContext) throws Exception {
        logger.info("Test: start falls back for unknown topic");
        String topic = "test-sub-unknown-" + System.nanoTime();
        String groupName = "sub-3";

        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, null, null,
                connectionManager, SERVICE_ID
        );
        group.setMessageHandler(msg -> Future.succeededFuture());

        group.start();

        databaseService.getVertx().timer(3000).mapEmpty()
                .map(v -> {
                    assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                            "Group should be ACTIVE via reference counting fallback");
                    return (Void) null;
                })
                .eventually(() -> { group.close(); return Future.succeededFuture(); })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // isOffsetWatermarkTopic returns false for PUB_SUB topic
    // ========================================================================

    @Test
    @DisplayName("start() uses reference counting for PUB_SUB topic")
    void startUsesReferenceCountingForPubSubTopic(VertxTestContext testContext) throws Exception {
        logger.info("Test: start uses reference counting for pub sub topic");
        String topic = "test-sub-pubsub-" + System.nanoTime();
        String groupName = "sub-4";

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, null,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    group.start();

                    return databaseService.getVertx().timer(3000).mapEmpty()
                            .map(delayed -> {
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                        "Group should be ACTIVE in reference counting mode for PUB_SUB topic");
                                return (Void) null;
                            })
                            .eventually(() -> { group.close(); return Future.succeededFuture(); });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // start(SubscriptionOptions) recover path subscription creation fails
    // ========================================================================

    @Test
    @DisplayName("start(SubscriptionOptions) recovers when topic does not exist in outbox_topics")
    void startWithSubscriptionRecoverOnMissingTopic(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with subscription recover on missing topic");
        String topic = "test-sub-recover-" + System.nanoTime();
        String groupName = "sub-5";

        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, mapper, null, null, databaseService,
                connectionManager, SERVICE_ID
        );
        group.setMessageHandler(msg -> Future.succeededFuture());

        group.start(SubscriptionOptions.defaults())
                .transform(ar -> {
                    if (ar.failed()) {
                        assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                "State should reset to NEW after subscription failure");
                    } else {
                        assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                "If subscription succeeds, group should be ACTIVE");
                    }
                    return Future.<Void>succeededFuture();
                })
                .eventually(() -> { group.close(); return Future.succeededFuture(); })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        testTopics.add(topic);
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                                "VALUES ($1, 'PUB_SUB', $2) ON CONFLICT (topic) DO NOTHING"
                ).execute(Tuple.of(topic, completionTrackingMode))
                        .map(rows -> (Void) null)
        );
    }

    private Future<Void> cleanupTestData() {
        if (testTopics.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] topics = testTopics.toArray(new String[0]);
        return connectionManager.withConnection(SERVICE_ID, conn ->
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
