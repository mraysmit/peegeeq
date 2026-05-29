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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

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
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        // Initialize database schema
        logger.info("Initializing database schema for dead consumer detection demo");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
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
                .schema("public")
                .build();

            PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(10)
                .build();

            connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

            // Create service instances
            topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
            subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
            deadConsumerDetector = new DeadConsumerDetector(connectionManager, "peegeeq-main");

            logger.info("Dead consumer detection demo test setup complete");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (connectionManager != null ? connectionManager.close() : io.vertx.core.Future.succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : io.vertx.core.Future.succeededFuture())
            .onSuccess(v -> {
                logger.info("Test teardown completed");
                testContext.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
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
        logger.info("\n=== DEMO 1: Heartbeat Configuration ===\n");

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

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info(" Topic created successfully");
                logger.info("\nStep 2: Subscribing '{}' with custom heartbeat settings", consumerGroup);
                return subscriptionManager.subscribe(topic, consumerGroup, heartbeatOptions);
            })
            .compose(v -> {
                logger.info(" Subscription created with custom heartbeat settings");
                logger.info("\nStep 3: Verifying heartbeat configuration");
                return subscriptionManager.getSubscriptionInternal(topic, consumerGroup);
            })
            .onSuccess(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "Subscription should exist");
                assertEquals(30, subscription.getHeartbeatIntervalSeconds(),
                    "Heartbeat interval should be 30 seconds");
                assertEquals(120, subscription.getHeartbeatTimeoutSeconds(),
                    "Heartbeat timeout should be 120 seconds");
                assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus(),
                    "Subscription should be ACTIVE");
                logger.info(" Verified heartbeat configuration:");
                logger.info("  - Interval: {} seconds", subscription.getHeartbeatIntervalSeconds());
                logger.info("  - Timeout: {} seconds", subscription.getHeartbeatTimeoutSeconds());
                logger.info("  - Status: {}", subscription.getStatus());
                logger.info("\n=== DEMO 1 COMPLETE: Heartbeat Configuration ===\n");
                logger.info("Key Takeaway: Configure heartbeat settings based on your processing characteristics.");
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
    void testDeadConsumerDetection(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("\n=== DEMO 2: Dead Consumer Detection ===\n");

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

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info(" Topic created successfully");
                logger.info("\nStep 2: Subscribing two consumer groups");
                return subscriptionManager.subscribe(topic, healthyGroup, shortTimeoutOptions);
            })
            .compose(v -> subscriptionManager.subscribe(topic, deadGroup, shortTimeoutOptions))
            .compose(v -> {
                logger.info(" Both consumer groups subscribed");
                logger.info("\nStep 3: Healthy consumer sends heartbeat");
                return subscriptionManager.updateHeartbeat(topic, healthyGroup);
            })
            .compose(v -> {
                logger.info(" Heartbeat sent by '{}'", healthyGroup);
                logger.info("\nStep 4: Waiting 12 seconds for dead consumer timeout...");
                return vertx.timer(4000);
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, healthyGroup))
            .compose(v -> vertx.timer(4000))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, healthyGroup))
            .compose(v -> vertx.timer(4000))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, healthyGroup))
            .compose(v -> {
                logger.info(" Timeout period elapsed (12 seconds)");
                logger.info("\nStep 5: Running dead consumer detection");
                return deadConsumerDetector.detectDeadSubscriptions(topic);
            })
            .compose(deadCount -> {
                logger.info(" Dead consumer detection complete - marked {} subscriptions as DEAD", deadCount);
                logger.info("\nStep 6: Retrieving dead subscriptions");
                return subscriptionManager.listSubscriptionsInternal(topic)
                    .compose(allSubs -> subscriptionManager.getSubscriptionInternal(topic, healthyGroup)
                        .onSuccess(healthySub -> testContext.verify(() -> {
                            List<Subscription> deadSubs = allSubs.stream()
                                .filter(sub -> sub.getStatus() == SubscriptionStatus.DEAD)
                                .toList();
                            logger.info(" Found {} dead subscriptions", deadSubs.size());
                            logger.info("\nStep 7: Verifying dead consumer detection");
                            assertEquals(1, (int) deadCount, "Should have marked exactly 1 subscription as DEAD");
                            assertEquals(1, deadSubs.size(), "Should have exactly 1 dead subscription");
                            Subscription deadSub = deadSubs.get(0);
                            assertEquals(deadGroup, deadSub.getGroupName(),
                                "Dead consumer should be '" + deadGroup + "'");
                            assertEquals(SubscriptionStatus.DEAD, deadSub.getStatus(), "Status should be DEAD");
                            logger.info(" Verified dead consumer detection:");
                            logger.info("  - Dead consumer: {}", deadSub.getGroupName());
                            logger.info("  - Status: {}", deadSub.getStatus());
                            logger.info("  - Last heartbeat: {}", deadSub.getLastHeartbeatAt());
                            assertEquals(SubscriptionStatus.ACTIVE, healthySub.getStatus(),
                                "Healthy consumer should still be ACTIVE");
                            logger.info(" Healthy consumer '{}' is still ACTIVE", healthyGroup);
                            logger.info("\n=== DEMO 2 COMPLETE: Dead Consumer Detection ===\n");
                            logger.info("Key Takeaway: Dead consumer detection automatically identifies consumers that stop sending heartbeats.");
                            testContext.completeNow();
                        })));
            })
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
    void testConsumerRecovery(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("\n=== DEMO 3: Consumer Recovery ===\n");

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

        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                logger.info(" Topic created successfully");
                logger.info("\nStep 2: Subscribing '{}'", consumerGroup);
                return subscriptionManager.subscribe(topic, consumerGroup, shortTimeoutOptions);
            })
            .compose(v -> {
                logger.info(" Consumer group subscribed");
                logger.info("\nStep 3: Verifying initial status");
                return subscriptionManager.getSubscriptionInternal(topic, consumerGroup);
            })
            .compose(initialSub -> {
                assertEquals(SubscriptionStatus.ACTIVE, initialSub.getStatus(), "Initial status should be ACTIVE");
                logger.info(" Initial status: {}", initialSub.getStatus());
                logger.info("\nStep 4: Simulating consumer crash (no heartbeats for 12 seconds)");
                return vertx.timer(12000);
            })
            .compose(v -> {
                logger.info(" Consumer has been 'crashed' for 12 seconds");
                logger.info("\nStep 5: Running dead consumer detection");
                return deadConsumerDetector.detectDeadSubscriptions(topic);
            })
            .compose(deadCount -> {
                logger.info(" Marked {} subscriptions as DEAD", deadCount);
                assertEquals(1, (int) deadCount, "Should have marked 1 subscription as DEAD");
                logger.info("\nStep 6: Verifying consumer is marked as DEAD");
                return subscriptionManager.getSubscriptionInternal(topic, consumerGroup);
            })
            .compose(deadSub -> {
                assertEquals(SubscriptionStatus.DEAD, deadSub.getStatus(), "Status should be DEAD after detection");
                logger.info(" Consumer status: {}", deadSub.getStatus());
                logger.info("\nStep 7: Recovering consumer by resuming subscription");
                return subscriptionManager.resume(topic, consumerGroup);
            })
            .compose(v -> {
                logger.info(" Subscription resumed");
                logger.info("\nStep 8: Verifying consumer is ACTIVE after recovery");
                return subscriptionManager.getSubscriptionInternal(topic, consumerGroup);
            })
            .compose(recoveredSub -> {
                assertEquals(SubscriptionStatus.ACTIVE, recoveredSub.getStatus(), "Status should be ACTIVE after resume");
                logger.info(" Consumer recovered successfully");
                logger.info("  - Status: {}", recoveredSub.getStatus());
                logger.info("  - Last active: {}", recoveredSub.getLastActiveAt());
                logger.info("\nStep 9: Sending heartbeat to keep consumer alive");
                return subscriptionManager.updateHeartbeat(topic, consumerGroup);
            })
            .onSuccess(v -> {
                logger.info(" Heartbeat sent - consumer is now healthy");
                logger.info("\n=== DEMO 3 COMPLETE: Consumer Recovery ===\n");
                logger.info("Key Takeaway: Dead consumers can be recovered by resuming the subscription and sending heartbeats.");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Test should complete within 60 seconds");
    }
}


