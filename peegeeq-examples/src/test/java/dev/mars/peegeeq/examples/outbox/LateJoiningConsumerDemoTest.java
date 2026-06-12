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
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize database schema
        logger.info("Initializing database schema for late-joining consumer demo");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create connection manager and pool
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
                .build();

            connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

            // Create service instances
            topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
            subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
            fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");

            logger.info("Late-joining consumer demo test setup complete");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (connectionManager != null ? connectionManager.close() : io.vertx.core.Future.<Void>succeededFuture())
            .transform(ar -> manager != null ? manager.closeReactive() : io.vertx.core.Future.succeededFuture())
            .onSuccess(v -> { logger.info("Test teardown completed"); testContext.completeNow(); })
            .onFailure(err -> { logger.warn("Teardown failed: {}", err.getMessage()); testContext.completeNow(); });
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
        logger.info("\n=== DEMO 1: FROM_NOW Consumer (Standard Pattern) ===\n");

        String topic = "orders.events";
        String emailGroup = "email-service";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        List<Long> historicalMessageIds = new ArrayList<>();
        List<Long> newMessageIds = new ArrayList<>();

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info("Step 2: Publishing 10 historical messages (before subscription)");
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
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
            .compose(v -> {
                logger.info(" Published {} historical messages", historicalMessageIds.size());
                logger.info("Step 3: Subscribing '{}' using FROM_NOW (default)", emailGroup);
                return subscriptionManager.subscribe(topic, emailGroup, SubscriptionOptions.defaults());
            })
            .compose(v -> {
                logger.info(" Subscription created with FROM_NOW");
                logger.info("Step 4: Publishing 5 new messages (after subscription)");
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
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
            .compose(v -> {
                logger.info(" Published {} new messages", newMessageIds.size());
                logger.info("Step 5: Fetching messages for '{}'", emailGroup);
                return fetcher.fetchMessages(topic, emailGroup, 20);
            })
            .onSuccess(messages -> testContext.verify(() -> {
                logger.info(" Fetched {} messages", messages.size());
                assertEquals(5, messages.size(), "FROM_NOW should only receive messages published after subscription");
                for (var msg : messages) {
                    assertTrue(newMessageIds.contains(msg.getId()),
                        "Message ID " + msg.getId() + " should be from new messages");
                    assertFalse(historicalMessageIds.contains(msg.getId()),
                        "Message ID " + msg.getId() + " should NOT be from historical messages");
                }
                logger.info(" Verified: FROM_NOW consumer only received {} new messages (ignored {} historical)",
                    messages.size(), historicalMessageIds.size());
                logger.info("\n=== DEMO 1 COMPLETE: FROM_NOW Pattern ===\n");
                logger.info("Key Takeaway: FROM_NOW consumers ignore historical messages and only process new ones.");
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
        logger.info("\n=== DEMO 2: FROM_BEGINNING Consumer (Late-Joining with Backfill) ===\n");

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

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info("Step 2: Subscribing '{}' using FROM_NOW", emailGroup);
                return subscriptionManager.subscribe(topic, emailGroup, SubscriptionOptions.defaults());
            })
            .compose(v -> {
                logger.info("Step 3: Publishing 20 messages");
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
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
            .compose(v -> {
                logger.info(" Published {} messages", allMessageIds.size());
                logger.info("Step 4: Late-joining '{}' subscribes using FROM_BEGINNING", analyticsGroup);
                return subscriptionManager.subscribe(topic, analyticsGroup, fromBeginningOptions);
            })
            .compose(v -> {
                logger.info("Step 5: Fetching messages for '{}'", analyticsGroup);
                return fetcher.fetchMessages(topic, analyticsGroup, 25);
            })
            .onSuccess(analyticsMessages -> testContext.verify(() -> {
                logger.info(" Fetched {} messages for analytics", analyticsMessages.size());
                assertEquals(20, analyticsMessages.size(),
                    "FROM_BEGINNING should receive ALL messages including historical");
                for (Long expectedId : allMessageIds) {
                    boolean found = analyticsMessages.stream()
                        .anyMatch(msg -> msg.getId().equals(expectedId));
                    assertTrue(found, "Analytics should have received message ID " + expectedId);
                }
                logger.info(" Verified: FROM_BEGINNING consumer received ALL {} historical messages",
                    analyticsMessages.size());
                logger.info("\n=== DEMO 2 COMPLETE: FROM_BEGINNING Pattern ===\n");
                logger.info("Key Takeaway: FROM_BEGINNING consumers backfill ALL historical messages from topic start.");
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
    void testFromTimestampConsumer(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("\n=== DEMO 3: FROM_TIMESTAMP Consumer (Time-Based Replay) ===\n");

        String topic = "orders.replay";
        String replayGroup = "disaster-recovery-service";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        List<Long> pastMessageIds = new ArrayList<>();

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info("Step 2: Publishing 10 messages (simulating past events)");
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
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
            .compose(v -> {
                logger.info(" Published {} past messages", pastMessageIds.size());
                Instant replayTimestamp = Instant.now().minus(1, ChronoUnit.HOURS);
                logger.info("Step 3: Recording replay timestamp: {}", replayTimestamp);
                SubscriptionOptions fromTimestampOptions = SubscriptionOptions.builder()
                    .startFromTimestamp(replayTimestamp)
                    .build();
                logger.info("Step 4: Publishing 10 messages after replay timestamp");
                return vertx.timer(100)
                    .compose(unused -> {
                        io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
                        for (int i = 11; i <= 20; i++) {
                            final int idx = i;
                            chain = chain.compose(unu ->
                                insertMessage(topic, new JsonObject()
                                    .put("orderId", "ORDER-" + idx)
                                    .put("amount", 100.0 + idx)
                                    .put("status", "CREATED"))
                                    .mapEmpty());
                        }
                        return chain;
                    })
                    .compose(unused -> {
                        logger.info("Step 5: Subscribing '{}' using FROM_TIMESTAMP", replayGroup);
                        return subscriptionManager.subscribe(topic, replayGroup, fromTimestampOptions);
                    });
            })
            .compose(v -> {
                logger.info("Step 6: Fetching messages for '{}'", replayGroup);
                return fetcher.fetchMessages(topic, replayGroup, 25);
            })
            .onSuccess(replayMessages -> testContext.verify(() -> {
                logger.info(" Fetched {} messages for replay", replayMessages.size());
                logger.info("Step 7: Verifying FROM_TIMESTAMP behavior");
                assertTrue(replayMessages.size() >= 10,
                    "FROM_TIMESTAMP should receive messages created at or after the timestamp");
                logger.info(" Verified: FROM_TIMESTAMP consumer received {} messages from timestamp onwards",
                    replayMessages.size());
                logger.info("\n=== DEMO 3 COMPLETE: FROM_TIMESTAMP Pattern ===\n");
                logger.info("Key Takeaway: FROM_TIMESTAMP consumers replay messages from a specific point in time.");
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

        return connectionManager.withConnection("peegeeq-main", connection ->
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
}


