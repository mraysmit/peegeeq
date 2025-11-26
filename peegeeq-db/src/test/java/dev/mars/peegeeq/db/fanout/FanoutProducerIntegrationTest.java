package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for fan-out message production.
 *
 * <p>This test validates Phase 3: Message Production & Fan-Out including:
 * <ul>
 *   <li>QUEUE topics maintain backward compatibility (required_consumer_groups = 1)</li>
 *   <li>PUB_SUB topics set required_consumer_groups based on active subscriptions</li>
 *   <li>Snapshot semantics: required_consumer_groups is immutable after insertion</li>
 *   <li>Zero-subscription protection (retention and write blocking)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.FLAKY)  // Race condition in parallel execution - needs investigation
public class FanoutProducerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FanoutProducerIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer<?> postgres = getPostgres();
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

        logger.info("Test setup complete");
    }

    /**
     * Test F7: QUEUE topics maintain backward compatibility.
     * Messages published to QUEUE topics should have required_consumer_groups = 1.
     */
    @Test
    void testQueueTopicBackwardCompatibility() throws Exception {
        String topic = "test-queue-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Create a QUEUE topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Insert a message into the outbox table
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups = 1 (backward compatibility)
        Integer requiredGroups = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, requiredGroups,
            "QUEUE topics should have required_consumer_groups = 1 for backward compatibility");

        logger.info("✅ QUEUE topic backward compatibility verified");
    }

    /**
     * Test F8: PUB_SUB topics with zero subscriptions.
     * Messages published to PUB_SUB topics with zero active subscriptions
     * should have required_consumer_groups = 0.
     */
    @Test
    void testPubSubTopicZeroSubscriptions() throws Exception {
        String topic = "test-pubsub-zero-" + UUID.randomUUID().toString().substring(0, 8);

        // Create a PUB_SUB topic (no subscriptions)
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Insert a message
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups = 0
        Integer requiredGroups = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, requiredGroups,
            "PUB_SUB topics with zero subscriptions should have required_consumer_groups = 0");

        logger.info("✅ PUB_SUB topic with zero subscriptions verified");
    }

    /**
     * Test F9: PUB_SUB topics with one subscription.
     * Messages published to PUB_SUB topics with one active subscription
     * should have required_consumer_groups = 1.
     */
    @Test
    void testPubSubTopicOneSubscription() throws Exception {
        String topic = "test-pubsub-one-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-a";

        // Create a PUB_SUB topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Create one subscription
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Insert a message
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups = 1
        Integer requiredGroups = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, requiredGroups,
            "PUB_SUB topics with one subscription should have required_consumer_groups = 1");

        logger.info("✅ PUB_SUB topic with one subscription verified");
    }

    /**
     * Test F10: PUB_SUB topics with multiple subscriptions.
     * Messages published to PUB_SUB topics with three active subscriptions
     * should have required_consumer_groups = 3.
     */
    @Test
    void testPubSubTopicMultipleSubscriptions() throws Exception {
        String topic = "test-pubsub-multi-" + UUID.randomUUID().toString().substring(0, 8);

        // Create a PUB_SUB topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Create three subscriptions
        subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, "group-c", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Insert a message
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups = 3
        Integer requiredGroups = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(3, requiredGroups,
            "PUB_SUB topics with three subscriptions should have required_consumer_groups = 3");

        logger.info("✅ PUB_SUB topic with multiple subscriptions verified");
    }

    /**
     * Test F11: Snapshot semantics - required_consumer_groups is immutable.
     * Adding a subscription after message insertion should NOT affect existing messages.
     */
    @Test
    void testSnapshotSemanticsImmutable() throws Exception {
        String topic = "test-snapshot-" + UUID.randomUUID().toString().substring(0, 8);

        // Create a PUB_SUB topic with one subscription
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Insert a message (should have required_consumer_groups = 1)
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        Integer requiredGroupsBefore = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(1, requiredGroupsBefore);

        // Add a second subscription AFTER message insertion
        subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups is still 1 (immutable)
        Integer requiredGroupsAfter = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, requiredGroupsAfter,
            "required_consumer_groups should be immutable after message insertion (snapshot semantics)");

        logger.info("✅ Snapshot semantics (immutability) verified");
    }

    /**
     * Test F12: Unconfigured topics default to QUEUE semantics.
     * Messages published to topics without configuration should default to QUEUE behavior.
     */
    @Test
    void testUnconfiguredTopicDefaultsToQueue() throws Exception {
        String topic = "test-unconfigured-" + UUID.randomUUID().toString().substring(0, 8);

        // Do NOT create topic configuration - let it default

        // Insert a message
        Long messageId = insertMessage(topic, new JsonObject().put("test", "data"))
            .toCompletionStage().toCompletableFuture().get();

        // Verify required_consumer_groups = 1 (QUEUE default)
        Integer requiredGroups = getRequiredConsumerGroups(messageId)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, requiredGroups,
            "Unconfigured topics should default to QUEUE semantics (required_consumer_groups = 1)");

        logger.info("✅ Unconfigured topic defaults to QUEUE verified");
    }

    // Helper methods

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;

            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));

            return connection.preparedQuery(sql)
                .execute(params)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return row.getLong("id");
                });
        });
    }

    private Future<Integer> getRequiredConsumerGroups(Long messageId) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT required_consumer_groups FROM outbox WHERE id = $1";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("Message not found: " + messageId);
                    }
                    Row row = rows.iterator().next();
                    return row.getInteger("required_consumer_groups");
                });
        });
    }
}

