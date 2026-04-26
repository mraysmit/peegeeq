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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.vertx.junit5.VertxTestContext;

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
@Tag(TestCategories.INTEGRATION)
public class FanoutProducerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FanoutProducerIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create service instances
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    /**
     * Test F7: QUEUE topics maintain backward compatibility.
     * Messages published to QUEUE topics should have required_consumer_groups = 1.
     */
    @Test
    void testQueueTopicBackwardCompatibility(VertxTestContext testContext) throws Exception {
        String topic = "test-queue-topic-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Create a QUEUE topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> insertMessage(topic, new JsonObject().put("test", "data")))
            .compose(messageId -> getRequiredConsumerGroups(messageId))
            .onSuccess(requiredGroups -> {
                try {
                    assertEquals(1, requiredGroups,
                        "QUEUE topics should have required_consumer_groups = 1 for backward compatibility");
                    logger.info("QUEUE topic backward compatibility verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Test F8: PUB_SUB topics with zero subscriptions.
     * Messages published to PUB_SUB topics with zero active subscriptions
     * should have required_consumer_groups = 0.
     */
    @Test
    void testPubSubTopicZeroSubscriptions(VertxTestContext testContext) throws Exception {
        String topic = "test-pubsub-zero-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Create a PUB_SUB topic (no subscriptions)
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> insertMessage(topic, new JsonObject().put("test", "data")))
            .compose(messageId -> getRequiredConsumerGroups(messageId))
            .onSuccess(requiredGroups -> {
                try {
                    assertEquals(0, requiredGroups,
                        "PUB_SUB topics with zero subscriptions should have required_consumer_groups = 0");
                    logger.info("PUB_SUB topic with zero subscriptions verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Test F9: PUB_SUB topics with one subscription.
     * Messages published to PUB_SUB topics with one active subscription
     * should have required_consumer_groups = 1.
     */
    @Test
    void testPubSubTopicOneSubscription(VertxTestContext testContext) throws Exception {
        String topic = "test-pubsub-one-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-a";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Create a PUB_SUB topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> insertMessage(topic, new JsonObject().put("test", "data")))
            .compose(messageId -> getRequiredConsumerGroups(messageId))
            .onSuccess(requiredGroups -> {
                try {
                    assertEquals(1, requiredGroups,
                        "PUB_SUB topics with one subscription should have required_consumer_groups = 1");
                    logger.info("PUB_SUB topic with one subscription verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Test F10: PUB_SUB topics with multiple subscriptions.
     * Messages published to PUB_SUB topics with three active subscriptions
     * should have required_consumer_groups = 3.
     */
    @Test
    void testPubSubTopicMultipleSubscriptions(VertxTestContext testContext) throws Exception {
        String topic = "test-pubsub-multi-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Create a PUB_SUB topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.subscribe(topic, "group-c", SubscriptionOptions.defaults()))
            .compose(v -> insertMessage(topic, new JsonObject().put("test", "data")))
            .compose(messageId -> getRequiredConsumerGroups(messageId))
            .onSuccess(requiredGroups -> {
                try {
                    assertEquals(3, requiredGroups,
                        "PUB_SUB topics with three subscriptions should have required_consumer_groups = 3");
                    logger.info("PUB_SUB topic with multiple subscriptions verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Test F11: Snapshot semantics - required_consumer_groups is immutable.
     * Adding a subscription after message insertion should NOT affect existing messages.
     */
    @Test
    void testSnapshotSemanticsImmutable(VertxTestContext testContext) throws Exception {
        String topic = "test-snapshot-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<Long> messageIdRef = new AtomicReference<>();

        // Create a PUB_SUB topic with one subscription
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults()))
            .compose(v -> insertMessage(topic, new JsonObject().put("test", "data")))
            .compose(messageId -> {
                messageIdRef.set(messageId);
                return getRequiredConsumerGroups(messageId);
            })
            .compose(before -> {
                try {
                    assertEquals(1, (int) before);
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.defaults())
                    .compose(v -> getRequiredConsumerGroups(messageIdRef.get()));
            })
            .onSuccess(requiredGroupsAfter -> {
                try {
                    assertEquals(1, (int) requiredGroupsAfter,
                        "required_consumer_groups should be immutable after message insertion (snapshot semantics)");
                    logger.info("Snapshot semantics (immutability) verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Test F12: Unconfigured topics default to QUEUE semantics.
     * Messages published to topics without configuration should default to QUEUE behavior.
     */
    @Test
    void testUnconfiguredTopicDefaultsToQueue(VertxTestContext testContext) throws Exception {
        String topic = "test-unconfigured-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Do NOT create topic configuration - let it default
        insertMessage(topic, new JsonObject().put("test", "data"))
            .compose(messageId -> getRequiredConsumerGroups(messageId))
            .onSuccess(requiredGroups -> {
                try {
                    assertEquals(1, requiredGroups,
                        "Unconfigured topics should default to QUEUE semantics (required_consumer_groups = 1)");
                    logger.info("Unconfigured topic defaults to QUEUE verified");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
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

