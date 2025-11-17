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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag(TestCategories.INTEGRATION)
class DeadConsumerDetectionDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionDemoTest.class);
    
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerDetector deadConsumerDetector;

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    /**
     * Clear system properties after test completion
     */
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    @BeforeEach
    void setUp() throws Exception {
        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Initialize database schema
        logger.info("Initializing database schema for dead consumer detection demo");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();

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
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            try {
                connectionManager.close();
            } catch (Exception e) {
                logger.warn("Error closing connection manager: {}", e.getMessage());
            }
        }
        if (manager != null) {
            manager.close();
        }

        // Clean up system properties
        clearSystemProperties();

        logger.info("Test teardown completed");
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
    @Order(1)
    void testHeartbeatConfiguration() throws Exception {
        logger.info("\n=== DEMO 1: Heartbeat Configuration ===\n");

        String topic = "orders.heartbeat";
        String consumerGroup = "order-processor";

        // Step 1: Create PUB_SUB topic
        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Topic created successfully");

        // Step 2: Subscribe with custom heartbeat settings
        logger.info("\nStep 2: Subscribing '{}' with custom heartbeat settings", consumerGroup);
        logger.info("  - Heartbeat interval: 30 seconds");
        logger.info("  - Heartbeat timeout: 120 seconds (2 minutes)");

        SubscriptionOptions heartbeatOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(30)   // Send heartbeat every 30 seconds
            .heartbeatTimeoutSeconds(120)   // Mark dead after 120 seconds without heartbeat
            .build();

        subscriptionManager.subscribe(topic, consumerGroup, heartbeatOptions)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Subscription created with custom heartbeat settings");

        // Step 3: Verify subscription configuration
        logger.info("\nStep 3: Verifying heartbeat configuration");
        Subscription subscription = subscriptionManager.getSubscription(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(subscription, "Subscription should exist");
        assertEquals(30, subscription.getHeartbeatIntervalSeconds(),
            "Heartbeat interval should be 30 seconds");
        assertEquals(120, subscription.getHeartbeatTimeoutSeconds(),
            "Heartbeat timeout should be 120 seconds");
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus(),
            "Subscription should be ACTIVE");

        logger.info("✓ Verified heartbeat configuration:");
        logger.info("  - Interval: {} seconds", subscription.getHeartbeatIntervalSeconds());
        logger.info("  - Timeout: {} seconds", subscription.getHeartbeatTimeoutSeconds());
        logger.info("  - Status: {}", subscription.getStatus());

        logger.info("\n=== ✅ DEMO 1 COMPLETE: Heartbeat Configuration ===\n");
        logger.info("Key Takeaway: Configure heartbeat settings based on your processing characteristics.");
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
    @Order(2)
    void testDeadConsumerDetection() throws Exception {
        logger.info("\n=== DEMO 2: Dead Consumer Detection ===\n");

        String topic = "orders.monitoring";
        String healthyGroup = "healthy-consumer";
        String deadGroup = "dead-consumer";

        // Step 1: Create PUB_SUB topic
        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Topic created successfully");

        // Step 2: Subscribe two consumer groups with short timeout for demo
        logger.info("\nStep 2: Subscribing two consumer groups");
        logger.info("  - '{}': Will send heartbeats (healthy)", healthyGroup);
        logger.info("  - '{}': Will NOT send heartbeats (will become dead)", deadGroup);

        SubscriptionOptions shortTimeoutOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(1)    // Very short for demo
            .heartbeatTimeoutSeconds(10)    // 10 seconds timeout for demo
            .build();

        subscriptionManager.subscribe(topic, healthyGroup, shortTimeoutOptions)
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, deadGroup, shortTimeoutOptions)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Both consumer groups subscribed");

        // Step 3: Healthy consumer sends heartbeat
        logger.info("\nStep 3: Healthy consumer sends heartbeat");
        subscriptionManager.updateHeartbeat(topic, healthyGroup)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Heartbeat sent by '{}'", healthyGroup);

        // Step 4: Wait for dead consumer timeout (dead consumer does NOT send heartbeat)
        logger.info("\nStep 4: Waiting 12 seconds for dead consumer timeout...");
        logger.info("  (Dead consumer '{}' is NOT sending heartbeats)", deadGroup);
        logger.info("  (Healthy consumer '{}' WILL send periodic heartbeats)", healthyGroup);

        // Send periodic heartbeats for healthy consumer during wait period
        for (int i = 0; i < 3; i++) {
            Thread.sleep(4000); // Wait 4 seconds
            subscriptionManager.updateHeartbeat(topic, healthyGroup)
                .toCompletionStage().toCompletableFuture().get();
            logger.info("  - Heartbeat #{} sent by '{}'", i + 1, healthyGroup);
        }

        logger.info("✓ Timeout period elapsed (12 seconds)");

        // Step 5: Run dead consumer detection
        logger.info("\nStep 5: Running dead consumer detection");
        int deadCount = deadConsumerDetector.detectDeadSubscriptions(topic)
            .toCompletionStage().toCompletableFuture().get();

        logger.info("✓ Dead consumer detection complete");
        logger.info("  - Marked {} subscriptions as DEAD", deadCount);

        // Step 6: Get list of all subscriptions and filter for DEAD ones
        logger.info("\nStep 6: Retrieving dead subscriptions");
        List<Subscription> allSubscriptions = subscriptionManager.listSubscriptions(topic)
            .toCompletionStage().toCompletableFuture().get();

        List<Subscription> deadSubscriptions = allSubscriptions.stream()
            .filter(sub -> sub.getStatus() == SubscriptionStatus.DEAD)
            .toList();

        logger.info("✓ Found {} dead subscriptions", deadSubscriptions.size());

        // Step 7: Verify dead consumer was detected
        logger.info("\nStep 7: Verifying dead consumer detection");
        assertEquals(1, deadCount, "Should have marked exactly 1 subscription as DEAD");
        assertEquals(1, deadSubscriptions.size(), "Should have exactly 1 dead subscription");

        Subscription deadSub = deadSubscriptions.get(0);
        assertEquals(deadGroup, deadSub.getGroupName(),
            "Dead consumer should be '" + deadGroup + "'");
        assertEquals(SubscriptionStatus.DEAD, deadSub.getStatus(),
            "Status should be DEAD");

        logger.info("✓ Verified dead consumer detection:");
        logger.info("  - Dead consumer: {}", deadSub.getGroupName());
        logger.info("  - Status: {}", deadSub.getStatus());
        logger.info("  - Last heartbeat: {}", deadSub.getLastHeartbeatAt());

        // Step 7: Verify healthy consumer is still ACTIVE
        logger.info("\nStep 7: Verifying healthy consumer is still ACTIVE");
        Subscription healthySub = subscriptionManager.getSubscription(topic, healthyGroup)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(SubscriptionStatus.ACTIVE, healthySub.getStatus(),
            "Healthy consumer should still be ACTIVE");
        logger.info("✓ Healthy consumer '{}' is still ACTIVE", healthyGroup);

        logger.info("\n=== ✅ DEMO 2 COMPLETE: Dead Consumer Detection ===\n");
        logger.info("Key Takeaway: Dead consumer detection automatically identifies consumers that stop sending heartbeats.");
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
    @Order(3)
    void testConsumerRecovery() throws Exception {
        logger.info("\n=== DEMO 3: Consumer Recovery ===\n");

        String topic = "orders.recovery";
        String consumerGroup = "recoverable-consumer";

        // Step 1: Create PUB_SUB topic
        logger.info("Step 1: Creating PUB_SUB topic '{}'", topic);
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Topic created successfully");

        // Step 2: Subscribe consumer group
        logger.info("\nStep 2: Subscribing '{}'", consumerGroup);
        SubscriptionOptions shortTimeoutOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(1)
            .heartbeatTimeoutSeconds(10)
            .build();

        subscriptionManager.subscribe(topic, consumerGroup, shortTimeoutOptions)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Consumer group subscribed");

        // Step 3: Verify initial status is ACTIVE
        logger.info("\nStep 3: Verifying initial status");
        Subscription initialSub = subscriptionManager.getSubscription(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionStatus.ACTIVE, initialSub.getStatus(),
            "Initial status should be ACTIVE");
        logger.info("✓ Initial status: {}", initialSub.getStatus());

        // Step 4: Simulate consumer crash (stop sending heartbeats)
        logger.info("\nStep 4: Simulating consumer crash (no heartbeats for 12 seconds)");
        Thread.sleep(12000); // Wait longer than timeout (10 seconds)
        logger.info("✓ Consumer has been 'crashed' for 12 seconds");

        // Step 5: Run dead consumer detection
        logger.info("\nStep 5: Running dead consumer detection");
        int deadCount = deadConsumerDetector.detectDeadSubscriptions(topic)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Marked {} subscriptions as DEAD", deadCount);
        assertEquals(1, deadCount, "Should have marked 1 subscription as DEAD");

        // Step 6: Verify consumer is now DEAD
        logger.info("\nStep 6: Verifying consumer is marked as DEAD");
        Subscription deadSub = subscriptionManager.getSubscription(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionStatus.DEAD, deadSub.getStatus(),
            "Status should be DEAD after detection");
        logger.info("✓ Consumer status: {}", deadSub.getStatus());

        // Step 7: Recover consumer by resuming subscription
        logger.info("\nStep 7: Recovering consumer by resuming subscription");
        subscriptionManager.resume(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Subscription resumed");

        // Step 8: Verify consumer is ACTIVE again
        logger.info("\nStep 8: Verifying consumer is ACTIVE after recovery");
        Subscription recoveredSub = subscriptionManager.getSubscription(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionStatus.ACTIVE, recoveredSub.getStatus(),
            "Status should be ACTIVE after resume");
        logger.info("✓ Consumer recovered successfully");
        logger.info("  - Status: {}", recoveredSub.getStatus());
        logger.info("  - Last active: {}", recoveredSub.getLastActiveAt());

        // Step 9: Send heartbeat to keep consumer alive
        logger.info("\nStep 9: Sending heartbeat to keep consumer alive");
        subscriptionManager.updateHeartbeat(topic, consumerGroup)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ Heartbeat sent - consumer is now healthy");

        logger.info("\n=== ✅ DEMO 3 COMPLETE: Consumer Recovery ===\n");
        logger.info("Key Takeaway: Dead consumers can be recovered by resuming the subscription and sending heartbeats.");
    }
}
