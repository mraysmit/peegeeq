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
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.subscription.Subscription;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.SubscriptionStatus;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.vertx.junit5.VertxTestContext;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Dead Consumer Detection patterns for PeeGeeQ Consumer Group Fanout.
 * 
 * <p>This test demonstrates the heartbeat-based dead consumer detection mechanism:</p>
 * <ul>
 *   <li><strong>Heartbeat Configuration</strong> - Configure heartbeat interval and timeout</li>
 *   <li><strong>Dead Consumer Detection</strong> - Automatic detection of consumers that stop sending heartbeats</li>
 *   <li><strong>Consumer Recovery</strong> - Reactivating dead consumers after recovery</li>
 * </ul>
 * 
 * <p><strong>Use Cases</strong>:</p>
 * <ul>
 *   <li>Detecting crashed consumer instances in production</li>
 *   <li>Monitoring consumer health and availability</li>
 *   <li>Automatic cleanup of stale consumer group subscriptions</li>
 *   <li>Alerting on consumer failures for operational teams</li>
 * </ul>
 * 
 * <p><strong>Key Concepts</strong>:</p>
 * <ul>
 *   <li>Heartbeat interval: How often consumers should send heartbeats (default: 60 seconds)</li>
 *   <li>Heartbeat timeout: How long to wait before marking consumer as DEAD (default: 300 seconds)</li>
 *   <li>Dead consumer detection runs periodically to identify timed-out consumers</li>
 *   <li>Dead consumers can be manually reactivated after recovery</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class DeadConsumerDetectionDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionDemoTest.class);
    
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerDetector deadConsumerDetector;

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
            deadConsumerDetector = new DeadConsumerDetector(connectionManager, "peegeeq-main");

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
                logger.error("Error during teardown", err);
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Scenario 1: Configure heartbeat settings for consumer groups.
     *
     * <p>This demonstrates how to configure custom heartbeat intervals and timeouts
     * when subscribing to a topic.</p>
     *
     * <p><strong>Use Case</strong>: Production consumer that needs custom heartbeat
     * settings based on processing characteristics.</p>
     */
    @Test
    void testHeartbeatConfiguration(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.heartbeat";
        String consumerGroup = "order-processor";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        SubscriptionOptions heartbeatOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(30)
            .heartbeatTimeoutSeconds(120)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, consumerGroup, heartbeatOptions))
            .compose(v -> subscriptionManager.getSubscriptionInternal(topic, consumerGroup))
            .onSuccess(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "Subscription should exist");
                assertEquals(30, subscription.getHeartbeatIntervalSeconds(),
                    "Heartbeat interval should be 30 seconds");
                assertEquals(120, subscription.getHeartbeatTimeoutSeconds(),
                    "Heartbeat timeout should be 120 seconds");
                assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus(),
                    "Subscription should be ACTIVE");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }

    /**
     * Scenario 2: Detect dead consumers that stop sending heartbeats.
     *
     * <p>This demonstrates the automatic dead consumer detection mechanism that
     * identifies consumers that have stopped sending heartbeats.</p>
     *
     * <p><strong>Use Case</strong>: Monitoring system that detects crashed consumer
     * instances and alerts operations team.</p>
     */
    @Test
    void testDeadConsumerDetection(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.monitoring";
        String healthyGroup = "healthy-consumer";
        String deadGroup = "dead-consumer";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        SubscriptionOptions shortTimeoutOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(1)
            .heartbeatTimeoutSeconds(10)
            .deadAfterMisses(1)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, healthyGroup, shortTimeoutOptions))
            .compose(v -> subscriptionManager.subscribe(topic, deadGroup, shortTimeoutOptions))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, healthyGroup))
            .compose(v -> setHeartbeatInPast(topic, deadGroup, 12))
            .compose(v -> deadConsumerDetector.detectDeadSubscriptions(topic))
            .compose(deadCount ->
                subscriptionManager.listSubscriptionsInternal(topic)
                    .compose(allSubscriptions ->
                        subscriptionManager.getSubscriptionInternal(topic, healthyGroup)
                            .map(healthySubscription -> new DetectionResult(
                                deadCount, allSubscriptions, healthySubscription))))
            .onSuccess(result -> testContext.verify(() -> {
                List<Subscription> deadSubscriptions = result.subscriptions().stream()
                    .filter(subscription -> subscription.getStatus() == SubscriptionStatus.DEAD)
                    .toList();
                assertEquals(1, result.deadCount(), "Should have marked exactly 1 subscription as DEAD");
                assertEquals(1, deadSubscriptions.size(), "Should have exactly 1 dead subscription");
                Subscription deadSubscription = deadSubscriptions.get(0);
                assertEquals(deadGroup, deadSubscription.getGroupName(),
                    "Dead consumer should be '" + deadGroup + "'");
                assertEquals(SubscriptionStatus.DEAD, deadSubscription.getStatus(), "Status should be DEAD");
                assertEquals(SubscriptionStatus.ACTIVE, result.healthySubscription().getStatus(),
                    "Healthy consumer should still be ACTIVE");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Test should complete within 60 seconds");
    }

    /**
     * Scenario 3: Recover a dead consumer by resuming the subscription.
     *
     * <p>This demonstrates how to reactivate a dead consumer after it has been
     * recovered or restarted.</p>
     *
     * <p><strong>Use Case</strong>: Consumer instance that crashed and was marked
     * as DEAD, then recovered and needs to resume processing.</p>
     */
    @Test
    void testConsumerRecovery(VertxTestContext testContext) throws InterruptedException {
        String topic = "orders.recovery";
        String consumerGroup = "recoverable-consumer";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        SubscriptionOptions shortTimeoutOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(1)
            .heartbeatTimeoutSeconds(10)
            .deadAfterMisses(1)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, consumerGroup, shortTimeoutOptions))
            .compose(v -> subscriptionManager.getSubscriptionInternal(topic, consumerGroup))
            .compose(initialSub -> {
                assertEquals(SubscriptionStatus.ACTIVE, initialSub.getStatus(), "Initial status should be ACTIVE");
                return setHeartbeatInPast(topic, consumerGroup, 12);
            })
            .compose(v -> deadConsumerDetector.detectDeadSubscriptions(topic))
            .compose(deadCount -> {
                assertEquals(1, (int) deadCount, "Should have marked 1 subscription as DEAD");
                return subscriptionManager.getSubscriptionInternal(topic, consumerGroup);
            })
            .compose(deadSub -> {
                assertEquals(SubscriptionStatus.DEAD, deadSub.getStatus(), "Status should be DEAD after detection");
                return subscriptionManager.resume(topic, consumerGroup);
            })
            .compose(v -> subscriptionManager.getSubscriptionInternal(topic, consumerGroup))
            .compose(recoveredSub -> {
                assertEquals(SubscriptionStatus.ACTIVE, recoveredSub.getStatus(), "Status should be ACTIVE after resume");
                return subscriptionManager.updateHeartbeat(topic, consumerGroup);
            })
            .compose(v -> subscriptionManager.getSubscriptionInternal(topic, consumerGroup))
            .onSuccess(healthySubscription -> testContext.verify(() -> {
                assertEquals(SubscriptionStatus.ACTIVE, healthySubscription.getStatus(),
                    "Recovered consumer should remain ACTIVE after heartbeat");
                assertNotNull(healthySubscription.getLastHeartbeatAt(),
                    "Recovered consumer heartbeat timestamp should be present");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Test should complete within 60 seconds");
    }

    private Future<Void> setHeartbeatInPast(String topic, String groupName, int secondsAgo) {
        OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsAgo);
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET last_heartbeat_at = $1
            WHERE topic = $2 AND group_name = $3
            """;
        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(pastTime, topic, groupName))
                .mapEmpty()
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

    private record DetectionResult(
            int deadCount,
            List<Subscription> subscriptions,
            Subscription healthySubscription) {
    }
}
