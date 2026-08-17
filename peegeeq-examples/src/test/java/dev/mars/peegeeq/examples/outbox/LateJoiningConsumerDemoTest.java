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
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.vertx.junit5.VertxTestContext;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Late-Joining Consumer patterns for PeeGeeQ Consumer Group Fanout.
 * 
 * <p>This test demonstrates the three core late-joining consumer scenarios:</p>
 * <ul>
 *   <li><strong>FROM_NOW</strong> - Standard consumer that only receives new messages</li>
 *   <li><strong>FROM_BEGINNING</strong> - Late-joining consumer that backfills all historical messages</li>
 *   <li><strong>FROM_TIMESTAMP</strong> - Time-based replay consumer that starts from a specific point in time</li>
 * </ul>
 * 
 * <p><strong>Use Cases</strong>:</p>
 * <ul>
 *   <li>Analytics service joining an existing topic to analyze historical data</li>
 *   <li>Audit service that needs complete message history</li>
 *   <li>Disaster recovery scenarios requiring replay from a specific timestamp</li>
 *   <li>New microservice joining an established event stream</li>
 * </ul>
 * 
 * <p><strong>Key Concepts</strong>:</p>
 * <ul>
 *   <li>PUB_SUB semantics ensure all consumer groups receive all messages</li>
 *   <li>Late-joining consumers trigger backfill operations for historical messages</li>
 *   <li>Snapshot semantics: required_consumer_groups is set at message insertion time</li>
 *   <li>Each consumer group maintains independent consumption progress</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class LateJoiningConsumerDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(LateJoiningConsumerDemoTest.class);
    
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start().map(v -> {
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
            subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
            fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");

            return (Void) null;
        })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        Future<Void> connectionManagerClose = connectionManager != null
            ? connectionManager.close()
            : Future.succeededFuture();
        connectionManagerClose
            .transform(connectionManagerResult -> {
                Future<Void> managerClose = manager != null
                    ? manager.closeReactive()
                    : Future.succeededFuture();
                return managerClose.transform(managerResult ->
                    mergeCloseResults(connectionManagerResult, managerResult));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Teardown failed", err);
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Scenario 1: Standard consumer using FROM_NOW (only receives new messages).
     *
     * <p>This is the most common pattern - consumers that only care about new messages
     * published after they subscribe. Historical messages are ignored.</p>
     *
     * <p><strong>Use Case</strong>: Email notification service that only sends emails
     * for new orders, not historical ones.</p>
     */
    @Test
    void testFromNowConsumer(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.events";
        String emailGroup = "email-service";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        List<Long> historicalMessageIds = new ArrayList<>();
        List<Long> newMessageIds = new ArrayList<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 10; i++) {
                    final int idx = i;
                    chain = chain.compose(unused ->
                        insertMessage(topic, new JsonObject()
                            .put("orderId", "ORDER-" + idx)
                            .put("amount", 100.0 + idx)
                            .put("status", "CREATED"))
                            .onSuccess(historicalMessageIds::add)
                            .mapEmpty());
                }
                return chain;
            })
            .compose(v -> subscriptionManager.subscribe(topic, emailGroup, SubscriptionOptions.defaults()))
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 11; i <= 15; i++) {
                    final int idx = i;
                    chain = chain.compose(unused ->
                        insertMessage(topic, new JsonObject()
                            .put("orderId", "ORDER-" + idx)
                            .put("amount", 100.0 + idx)
                            .put("status", "CREATED"))
                            .onSuccess(newMessageIds::add)
                            .mapEmpty());
                }
                return chain;
            })
            .compose(v -> fetcher.fetchMessages(topic, emailGroup, 20))
            .onSuccess(messages -> testContext.verify(() -> {
                assertEquals(5, messages.size(), "FROM_NOW should only receive messages published after subscription");
                for (var msg : messages) {
                    assertTrue(newMessageIds.contains(msg.getId()),
                        "Message ID " + msg.getId() + " should be from new messages");
                    assertFalse(historicalMessageIds.contains(msg.getId()),
                        "Message ID " + msg.getId() + " should NOT be from historical messages");
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }

    /**
     * Scenario 2: Late-joining consumer using FROM_BEGINNING (backfills all historical messages).
     *
     * <p>This pattern is used when a new consumer group needs to process ALL messages
     * from the beginning of the topic, including historical data.</p>
     *
     * <p><strong>Use Case</strong>: Analytics service joining an existing topic to
     * build reports from complete historical data.</p>
     */
    @Test
    void testFromBeginningConsumer(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.analytics";
        String emailGroup = "email-service";
        String analyticsGroup = "analytics-service";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        SubscriptionOptions fromBeginningOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        List<Long> allMessageIds = new ArrayList<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, emailGroup, SubscriptionOptions.defaults()))
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 20; i++) {
                    final int idx = i;
                    chain = chain.compose(unused ->
                        insertMessage(topic, new JsonObject()
                            .put("orderId", "ORDER-" + idx)
                            .put("amount", 100.0 + idx)
                            .put("status", "CREATED"))
                            .onSuccess(allMessageIds::add)
                            .mapEmpty());
                }
                return chain;
            })
            .compose(v -> subscriptionManager.subscribe(topic, analyticsGroup, fromBeginningOptions))
            .compose(v -> fetcher.fetchMessages(topic, analyticsGroup, 25))
            .onSuccess(analyticsMessages -> testContext.verify(() -> {
                assertEquals(20, analyticsMessages.size(),
                    "FROM_BEGINNING should receive ALL messages including historical");
                for (Long expectedId : allMessageIds) {
                    boolean found = analyticsMessages.stream()
                        .anyMatch(msg -> msg.getId().equals(expectedId));
                    assertTrue(found, "Analytics should have received message ID " + expectedId);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Test should complete within 60 seconds");
    }

    /**
     * Scenario 3: Time-based replay using FROM_TIMESTAMP.
     *
     * <p>This pattern allows consumers to start from a specific point in time,
     * useful for disaster recovery or time-based replay scenarios.</p>
     *
     * <p><strong>Use Case</strong>: Disaster recovery service that needs to replay
     * messages from a specific timestamp after a system failure.</p>
     */
    @Test
    void testFromTimestampConsumer(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.replay";
        String replayGroup = "disaster-recovery-service";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        List<Long> pastMessageIds = new ArrayList<>();
        List<Long> replayMessageIds = new ArrayList<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 10; i++) {
                    final int idx = i;
                    chain = chain.compose(unused ->
                        insertMessage(topic, new JsonObject()
                            .put("orderId", "ORDER-" + idx)
                            .put("amount", 100.0 + idx)
                            .put("status", "CREATED"))
                            .onSuccess(pastMessageIds::add)
                            .mapEmpty());
                }
                return chain;
            })
            .compose(v -> currentDatabaseTime())
            .compose(replayTimestamp -> {
                SubscriptionOptions fromTimestampOptions = SubscriptionOptions.builder()
                    .startFromTimestamp(replayTimestamp)
                    .build();
                Future<Void> chain = Future.succeededFuture();
                for (int i = 11; i <= 20; i++) {
                    final int idx = i;
                    chain = chain.compose(unused ->
                        insertMessage(topic, new JsonObject()
                            .put("orderId", "ORDER-" + idx)
                            .put("amount", 100.0 + idx)
                            .put("status", "CREATED"))
                            .onSuccess(replayMessageIds::add)
                            .mapEmpty());
                }
                return chain.compose(unused ->
                    subscriptionManager.subscribe(topic, replayGroup, fromTimestampOptions));
            })
            .compose(v -> fetcher.fetchMessages(topic, replayGroup, 25))
            .onSuccess(replayMessages -> testContext.verify(() -> {
                assertEquals(10, replayMessages.size(),
                    "FROM_TIMESTAMP should receive only messages created at or after the timestamp");
                for (var message : replayMessages) {
                    assertTrue(replayMessageIds.contains(message.getId()),
                        "Replay message ID should be from the post-boundary messages: " + message.getId());
                    assertFalse(pastMessageIds.contains(message.getId()),
                        "Replay must exclude pre-boundary message ID: " + message.getId());
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }

    // Helper Methods

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

    private Future<Instant> currentDatabaseTime() {
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.query("SELECT clock_timestamp() AS replay_timestamp")
                .execute()
                .map(rows -> rows.iterator().next()
                    .getOffsetDateTime("replay_timestamp")
                    .toInstant())
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

