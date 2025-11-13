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
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
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
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag(TestCategories.INTEGRATION)
class ZeroSubscriptionProtectionDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(ZeroSubscriptionProtectionDemoTest.class);
    
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private ZeroSubscriptionValidator zeroSubscriptionValidator;

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
        logger.info("Initializing database schema for zero-subscription protection demo");
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
        zeroSubscriptionValidator = new ZeroSubscriptionValidator(connectionManager, "peegeeq-main");

        logger.info("Zero-subscription protection demo test setup complete");
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
     * Scenario 1: QUEUE topics always allow writes (no zero-subscription protection).
     *
     * <p>QUEUE topics use distribution semantics, so zero-subscription protection
     * is not applicable. Messages are always accepted.</p>
     *
     * <p><strong>Use Case</strong>: Work queue where messages are distributed to
     * available workers. If no workers are available, messages wait in the queue.</p>
     */
    @Test
    @Order(1)
    void testQueueTopicAlwaysAllowsWrites() throws Exception {
        logger.info("\n=== DEMO 1: QUEUE Topics Always Allow Writes ===\n");

        String topic = "work.queue";

        // Step 1: Create QUEUE topic
        logger.info("Step 1: Creating QUEUE topic '{}'", topic);
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ QUEUE topic created successfully");

        // Step 2: Verify topic has zero subscriptions
        logger.info("\nStep 2: Verifying topic has zero subscriptions");
        boolean hasSubscriptions = hasActiveSubscriptions(topic);
        assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
        logger.info("✓ Confirmed: Topic has zero subscriptions");

        // Step 3: Attempt to insert message (should succeed for QUEUE topics)
        logger.info("\nStep 3: Inserting message into QUEUE topic with zero subscriptions");
        Long messageId = insertMessage(topic, new JsonObject()
            .put("taskId", "TASK-001")
            .put("action", "process-order"))
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(messageId, "Message should be inserted successfully");
        logger.info("✓ Message inserted successfully: ID = {}", messageId);

        // Step 4: Verify write was allowed
        logger.info("\nStep 4: Verifying QUEUE topic behavior");
        logger.info("✓ QUEUE topics ALWAYS allow writes, regardless of subscription count");
        logger.info("  - Reason: Messages are distributed to workers when they become available");
        logger.info("  - Zero-subscription protection does NOT apply to QUEUE topics");

        logger.info("\n=== ✅ DEMO 1 COMPLETE: QUEUE Topics ===\n");
        logger.info("Key Takeaway: QUEUE topics always accept messages, even with zero subscriptions.");
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
    @Order(2)
    void testPubSubAllowsWritesWithoutSubscriptions() throws Exception {
        logger.info("\n=== DEMO 2: PUB_SUB with blockWritesOnZeroSubscriptions=false ===\n");

        String topic = "events.stream";

        // Step 1: Create PUB_SUB topic with write blocking disabled
        logger.info("Step 1: Creating PUB_SUB topic with blockWritesOnZeroSubscriptions=false");
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(12)  // Retain for 12 hours
            .blockWritesOnZeroSubscriptions(false)  // Allow writes
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ PUB_SUB topic created successfully");
        logger.info("  - blockWritesOnZeroSubscriptions: false");
        logger.info("  - zeroSubscriptionRetentionHours: 12");

        // Step 2: Verify topic has zero subscriptions
        logger.info("\nStep 2: Verifying topic has zero subscriptions");
        boolean hasSubscriptions = hasActiveSubscriptions(topic);
        assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
        logger.info("✓ Confirmed: Topic has zero subscriptions");

        // Step 3: Insert message (should succeed)
        logger.info("\nStep 3: Inserting message into PUB_SUB topic with zero subscriptions");
        Long messageId = insertMessage(topic, new JsonObject()
            .put("eventId", "EVENT-001")
            .put("eventType", "order.created"))
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(messageId, "Message should be inserted successfully");
        logger.info("✓ Message inserted successfully: ID = {}", messageId);

        // Step 4: Verify write was allowed
        logger.info("\nStep 4: Verifying PUB_SUB behavior with blockWritesOnZeroSubscriptions=false");
        logger.info("✓ Write was ALLOWED even with zero subscriptions");
        logger.info("  - Message will be retained for 12 hours (zeroSubscriptionRetentionHours)");
        logger.info("  - Late-joining consumers can backfill using FROM_BEGINNING");

        logger.info("\n=== ✅ DEMO 2 COMPLETE: PUB_SUB Allows Writes ===\n");
        logger.info("Key Takeaway: PUB_SUB topics with blockWritesOnZeroSubscriptions=false allow writes and retain messages for late-joining consumers.");
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
    @Order(3)
    void testPubSubBlocksWritesWithoutSubscriptions() throws Exception {
        logger.info("\n=== DEMO 3: PUB_SUB with blockWritesOnZeroSubscriptions=true ===\n");

        String topic = "critical.events";

        // Step 1: Create PUB_SUB topic with write blocking enabled
        logger.info("Step 1: Creating PUB_SUB topic with blockWritesOnZeroSubscriptions=true");
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(24)
            .blockWritesOnZeroSubscriptions(true)  // Block writes
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();
        logger.info("✓ PUB_SUB topic created successfully");
        logger.info("  - blockWritesOnZeroSubscriptions: true");
        logger.info("  - zeroSubscriptionRetentionHours: 24");

        // Step 2: Verify topic has zero subscriptions
        logger.info("\nStep 2: Verifying topic has zero subscriptions");
        boolean hasSubscriptions = hasActiveSubscriptions(topic);
        assertFalse(hasSubscriptions, "Topic should have zero subscriptions");
        logger.info("✓ Confirmed: Topic has zero subscriptions");

        // Step 3: Check if write is allowed (should be blocked)
        logger.info("\nStep 3: Checking if write is allowed for PUB_SUB topic with zero subscriptions");
        logger.info("  (This should be blocked due to write blocking)");

        Boolean writeAllowed = zeroSubscriptionValidator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertFalse(writeAllowed, "Write should be blocked when zero subscriptions and blockWritesOnZeroSubscriptions=true");
        logger.info("✓ Write was BLOCKED as expected");
        logger.info("  - isWriteAllowed() returned: false");

        // Step 4: Verify write blocking behavior
        logger.info("\nStep 4: Verifying PUB_SUB behavior with blockWritesOnZeroSubscriptions=true");
        logger.info("✓ Write blocking is working correctly");
        logger.info("  - Prevents accidental data loss when no consumers are listening");
        logger.info("  - Ensures critical events are only published to active subscribers");

        logger.info("\n=== ✅ DEMO 3 COMPLETE: PUB_SUB Blocks Writes ===\n");
        logger.info("Key Takeaway: PUB_SUB topics with blockWritesOnZeroSubscriptions=true prevent writes when no active subscriptions exist.");
    }

    // Helper Methods

    /**
     * Checks if a topic has any active subscriptions.
     */
    private boolean hasActiveSubscriptions(String topic) throws Exception {
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
        ).toCompletionStage().toCompletableFuture().get();
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

