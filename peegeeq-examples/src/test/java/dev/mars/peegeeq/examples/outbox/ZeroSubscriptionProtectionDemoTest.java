package dev.mars.peegeeq.examples.outbox;

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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.db.subscription.ZeroSubscriptionValidator;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Zero-Subscription Protection patterns for PeeGeeQ Consumer Group Fanout.
 * 
 * <p>This test demonstrates the zero-subscription protection mechanism for PUB_SUB topics:</p>
 * <ul>
 *   <li><strong>QUEUE Topics</strong> - Always allow writes (no protection needed)</li>
 *   <li><strong>PUB_SUB with blockWritesOnZeroSubscriptions=false</strong> - Allow writes, retain for configured period</li>
 *   <li><strong>PUB_SUB with blockWritesOnZeroSubscriptions=true</strong> - Block writes when no active subscriptions</li>
 * </ul>
 * 
 * <p><strong>Use Cases</strong>:</p>
 * <ul>
 *   <li>Preventing data loss when all consumers are temporarily down</li>
 *   <li>Ensuring critical events are not published to topics with no listeners</li>
 *   <li>Configurable retention for messages published before first subscription</li>
 *   <li>Operational safety for PUB_SUB topics in production</li>
 * </ul>
 * 
 * <p><strong>Key Concepts</strong>:</p>
 * <ul>
 *   <li>QUEUE topics never block writes (messages are distributed, not replicated)</li>
 *   <li>PUB_SUB topics can optionally block writes when no active subscriptions exist</li>
 *   <li>Zero-subscription retention: minimum time to keep messages before cleanup (default: 24 hours)</li>
 *   <li>Write blocking prevents accidental data loss for critical event streams</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Testcontainers
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class ZeroSubscriptionProtectionDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(ZeroSubscriptionProtectionDemoTest.class);
    
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private ZeroSubscriptionValidator zeroSubscriptionValidator;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());

        manager.start()
            .map(v -> {
                connectionManager = new PgConnectionManager(manager.getVertx(), null);
                PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
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

                connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

                topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
                zeroSubscriptionValidator = new ZeroSubscriptionValidator(connectionManager, "peegeeq-main");

                return (Void) null;
            })
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> cmClose = connectionManager != null
            ? connectionManager.close()
            : Future.succeededFuture();
        cmClose
            .transform(connectionManagerResult -> {
                Future<Void> managerClose = manager != null
                    ? manager.closeReactive()
                    : Future.succeededFuture();
                return managerClose.transform(managerResult ->
                    mergeCloseResults(connectionManagerResult, managerResult));
            })
            .onSuccess(v -> ctx.completeNow())
            .onFailure(err -> {
                logger.error("Error closing test resources", err);
                ctx.failNow(err);
            });
    }

    /**
     * Scenario 1: QUEUE topics always allow writes (no zero-subscription protection).
     *
     * <p>QUEUE topics use distribution semantics, so zero-subscription protection
     * is not applicable. Messages are always accepted.</p>
     *
     * <p><strong>Use Case</strong>: Work queue where messages are distributed to
     * available workers. If no workers are available, messages wait in the queue.</p>
     */
    @Test
    void testQueueTopicAlwaysAllowsWrites(VertxTestContext testContext) throws Exception {
        String topic = "work.queue";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> hasActiveSubscriptions(topic))
            .compose(hasSubscriptions -> {
                assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
                return insertMessage(topic, new JsonObject()
                    .put("taskId", "TASK-001")
                    .put("action", "process-order"));
            })
            .onSuccess(messageId -> testContext.verify(() -> {
                assertNotNull(messageId, "Message should be inserted successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "Test should complete within 30 seconds");
    }

    /**
     * Scenario 2: PUB_SUB topic with blockWritesOnZeroSubscriptions=false (allows writes).
     *
     * <p>This configuration allows writes to PUB_SUB topics even when there are no
     * active subscriptions. Messages are retained for the configured period.</p>
     *
     * <p><strong>Use Case</strong>: Event stream where late-joining consumers can
     * backfill historical events using FROM_BEGINNING.</p>
     */
    @Test
    void testPubSubAllowsWritesWithoutSubscriptions(VertxTestContext testContext) throws Exception {
        String topic = "events.stream";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(12)  // Retain for 12 hours
            .blockWritesOnZeroSubscriptions(false)  // Allow writes
            .build();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> hasActiveSubscriptions(topic))
            .compose(hasSubscriptions -> {
                assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
                return insertMessage(topic, new JsonObject()
                    .put("eventId", "EVENT-001")
                    .put("eventType", "order.created"));
            })
            .onSuccess(messageId -> testContext.verify(() -> {
                assertNotNull(messageId, "Message should be inserted successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "Test should complete within 30 seconds");
    }

    /**
     * Scenario 3: PUB_SUB topic with blockWritesOnZeroSubscriptions=true (blocks writes).
     *
     * <p>This configuration blocks writes to PUB_SUB topics when there are no
     * active subscriptions, preventing accidental data loss.</p>
     *
     * <p><strong>Use Case</strong>: Critical event stream where messages should
     * only be published when there are active consumers listening.</p>
     */
    @Test
    void testPubSubBlocksWritesWithoutSubscriptions(VertxTestContext testContext) throws Exception {
        String topic = "critical.events";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(24)
            .blockWritesOnZeroSubscriptions(true)  // Block writes
            .build();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> hasActiveSubscriptions(topic))
            .compose(hasSubscriptions -> {
                assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
                return zeroSubscriptionValidator.isWriteAllowed(topic);
            })
            .onSuccess(writeAllowed -> testContext.verify(() -> {
                assertFalse(writeAllowed,
                    "Write should be blocked when zero subscriptions and blockWritesOnZeroSubscriptions=true");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "Test should complete within 30 seconds");
    }

    // Helper Methods

    /**
     * Checks if a topic has any active subscriptions.
     */
    private Future<Boolean> hasActiveSubscriptions(String topic) {
        String sql = """
            SELECT COUNT(*) AS sub_count
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND subscription_status = 'ACTIVE'
            """;

        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    if (rows.size() > 0) {
                        long count = rows.iterator().next().getLong("sub_count");
                        return count > 0;
                    }
                    return false;
                })
        );
    }

    /**
     * Inserts a message into the outbox table.
     */
    private io.vertx.core.Future<Long> insertMessage(String topic, JsonObject payload) {
        String sql = """
            INSERT INTO outbox (topic, payload, status)
            VALUES ($1, $2, 'PENDING')
            RETURNING id
            """;

        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, payload))
                .map(rows -> {
                    if (rows.size() > 0) {
                        return rows.iterator().next().getLong("id");
                    }
                    throw new RuntimeException("Failed to insert message");
                })
        );
    }

    private Future<Void> mergeCloseResults(
            AsyncResult<Void> connectionManagerResult,
            AsyncResult<Void> managerResult) {
        if (connectionManagerResult.failed()) {
            Throwable failure = connectionManagerResult.cause();
            if (managerResult.failed() && managerResult.cause() != failure) {
                failure.addSuppressed(managerResult.cause());
            }
            return Future.failedFuture(failure);
        }
        if (managerResult.failed()) {
            return Future.failedFuture(managerResult.cause());
        }
        return Future.succeededFuture();
    }
}

