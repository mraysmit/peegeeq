package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SubscriptionManager.
 *
 * <p>This test validates the subscription management API including:
 * <ul>
 *   <li>Creating subscriptions with different start positions</li>
 *   <li>Pausing and resuming subscriptions</li>
 *   <li>Cancelling subscriptions</li>
 *   <li>Updating heartbeats</li>
 *   <li>Querying subscription status</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class SubscriptionManagerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerIntegrationTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

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

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("SubscriptionManager test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }
    
    @Test
    void testSubscribeWithDefaultOptions(VertxTestContext testContext) {
        logger.info("=== Testing subscribe with default options ===");

        String topic = "test-topic-default";
        String groupName = "test-group-1";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "Subscription should be created");
                assertEquals(topic, subscription.topic());
                assertEquals(groupName, subscription.groupName());
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertNotNull(subscription.subscribedAt());
                assertNotNull(subscription.lastHeartbeatAt());
                logger.info("Subscribe with default options test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSubscribeWithCustomOptions(VertxTestContext testContext) {
        logger.info("=== Testing subscribe with custom options ===");

        String topic = "test-topic-custom";
        String groupName = "test-group-2";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .heartbeatIntervalSeconds(30)
            .heartbeatTimeoutSeconds(120)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(30, subscription.heartbeatIntervalSeconds());
                assertEquals(120, subscription.heartbeatTimeoutSeconds());
                logger.info("Subscribe with custom options test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testPauseAndResumeSubscription(VertxTestContext testContext) {
        logger.info("=== Testing pause and resume subscription ===");

        String topic = "test-topic-pause";
        String groupName = "test-group-3";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(pausedSubscription -> {
                assertEquals(SubscriptionState.PAUSED, pausedSubscription.state());
                assertFalse(pausedSubscription.isActive());
                return subscriptionManager.resume(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(resumedSubscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, resumedSubscription.state());
                assertTrue(resumedSubscription.isActive());
                logger.info("Pause and resume subscription test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testCancelSubscription(VertxTestContext testContext) {
        logger.info("=== Testing cancel subscription ===");

        String topic = "test-topic-cancel";
        String groupName = "test-group-4";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(cancelledSubscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, cancelledSubscription.state());
                assertFalse(cancelledSubscription.isActive());
                logger.info("Cancel subscription test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testUpdateHeartbeat(VertxTestContext testContext) {
        logger.info("=== Testing update heartbeat ===");

        String topic = "test-topic-heartbeat";
        String groupName = "test-group-5";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        AtomicReference<Instant> initialHeartbeatRef = new AtomicReference<>();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(initial -> {
                initialHeartbeatRef.set(initial.lastHeartbeatAt());
                return manager.getVertx().timer(100);
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(updatedSubscription -> testContext.verify(() -> {
                assertTrue(updatedSubscription.lastHeartbeatAt().isAfter(initialHeartbeatRef.get()),
                    "Heartbeat timestamp should be updated");
                logger.info("Update heartbeat test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testListSubscriptions(VertxTestContext testContext) {
        logger.info("=== Testing list subscriptions ===");

        String topic = "test-topic-list";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, "group-a"))
            .compose(v -> subscriptionManager.subscribe(topic, "group-b"))
            .compose(v -> subscriptionManager.subscribe(topic, "group-c"))
            .compose(v -> subscriptionManager.listSubscriptions(topic))
            .onSuccess(subscriptions -> testContext.verify(() -> {
                assertEquals(3, subscriptions.size(), "Should have 3 subscriptions");
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-a")));
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-b")));
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-c")));
                logger.info("List subscriptions test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testHeartbeatResurrectsDeadSubscription(VertxTestContext testContext) {
        logger.info("=== Testing heartbeat auto-resurrects DEAD subscription ===");

        String topic = "test-topic-resurrect";
        String groupName = "test-group-resurrect";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        AtomicReference<Instant> deadHeartbeatRef = new AtomicReference<>();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(initial -> {
                assertEquals(SubscriptionState.ACTIVE, initial.state(), "Should start as ACTIVE");
                return setSubscriptionStatus(topic, groupName, "DEAD");
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(dead -> {
                assertEquals(SubscriptionState.DEAD, dead.state(), "Should be DEAD after manual update");
                deadHeartbeatRef.set(dead.lastHeartbeatAt());
                return manager.getVertx().timer(100);
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(resurrected -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, resurrected.state(),
                    "Should be ACTIVE after heartbeat resurrects DEAD subscription");
                assertTrue(resurrected.lastHeartbeatAt().isAfter(deadHeartbeatRef.get()),
                    "Heartbeat timestamp should be updated");
                logger.info("Heartbeat auto-resurrection test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testHeartbeatDoesNotResurrectCancelledSubscription(VertxTestContext testContext) {
        logger.info("=== Testing heartbeat does NOT resurrect CANCELLED subscription ===");

        String topic = "test-topic-no-resurrect";
        String groupName = "test-group-no-resurrect";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(cancelled -> {
                assertEquals(SubscriptionState.CANCELLED, cancelled.state(), "Should be CANCELLED");
                return subscriptionManager.updateHeartbeat(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(stillCancelled -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, stillCancelled.state(),
                    "CANCELLED subscription should NOT be resurrected by heartbeat");
                logger.info("Heartbeat does not resurrect CANCELLED test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testHeartbeatKeepsPausedSubscriptionPaused(VertxTestContext testContext) {
        logger.info("=== Testing heartbeat keeps PAUSED subscription PAUSED ===");

        String topic = "test-topic-paused-hb";
        String groupName = "test-group-paused-hb";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(paused -> {
                assertEquals(SubscriptionState.PAUSED, paused.state(), "Should be PAUSED");
                return subscriptionManager.updateHeartbeat(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(stillPaused -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, stillPaused.state(),
                    "PAUSED subscription should remain PAUSED after heartbeat");
                logger.info("Heartbeat keeps PAUSED subscription PAUSED test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    // --- Backfill Lifecycle Integration Tests (H2) ---

    @Test
    void testSubscribeFromBeginningAutoTriggersBackfill(VertxTestContext testContext) {
        logger.info("=== Testing subscribe FROM_BEGINNING auto-triggers backfill ===");

        String topic = "test-backfill-auto-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-auto-group";
        int messageCount = 5;

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
            .compose(v -> {
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(info -> {
                assertNotNull(info, "Subscription should exist");
                assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
                assertEquals("COMPLETED", info.backfillStatus(),
                    "Backfill should have been auto-triggered and completed");
                assertEquals(messageCount, info.backfillProcessedMessages(),
                    "All " + messageCount + " messages should have been backfilled");
                return countMessagesWithRequiredGroups(topic, 2);
            })
            .onSuccess(incrementedCount -> testContext.verify(() -> {
                assertTrue(incrementedCount > 0,
                    "Messages should have required_consumer_groups incremented to 2");
                logger.info("Subscribe FROM_BEGINNING auto-triggers backfill test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSubscribeFromNowDoesNotTriggerBackfill(VertxTestContext testContext) {
        logger.info("=== Testing subscribe FROM_NOW does NOT trigger backfill ===");

        String topic = "test-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-backfill-group";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> {
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < 3; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(info -> testContext.verify(() -> {
                assertNotNull(info, "Subscription should exist");
                assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
                assertTrue(info.backfillStatus() == null || "NONE".equals(info.backfillStatus()),
                    "Backfill should NOT have been triggered for FROM_NOW, got: " + info.backfillStatus());
                logger.info("Subscribe FROM_NOW does not trigger backfill test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSubscribeFromBeginningWithoutBackfillServiceStillWorks(VertxTestContext testContext) {
        logger.info("=== Testing subscribe FROM_BEGINNING without BackfillService ===");

        String topic = "test-no-svc-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-svc-group";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(info -> testContext.verify(() -> {
                assertNotNull(info, "Subscription should exist");
                assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
                assertEquals(1L, info.startFromMessageId(), "start_from_message_id should be 1 for FROM_BEGINNING");
                logger.info("Subscribe FROM_BEGINNING without BackfillService test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    // --- Helper methods ---

    /**
     * Directly sets subscription status via SQL (for test setup).
     */
    private Future<Void> setSubscriptionStatus(String topic, String groupName, String status) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $1
                WHERE topic = $2 AND group_name = $3
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(status, topic, groupName))
                .mapEmpty();
        });
    }

    /**
     * Inserts a test message into the outbox.
     */
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
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    /**
     * Counts messages with a specific required_consumer_groups value.
     */
    private Future<Long> countMessagesWithRequiredGroups(String topic, int requiredGroups) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox
                WHERE topic = $1 AND required_consumer_groups = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, requiredGroups))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }
}

