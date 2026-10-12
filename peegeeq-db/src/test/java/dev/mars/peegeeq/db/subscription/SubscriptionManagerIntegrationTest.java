package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
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
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "Subscription should be created");
                assertEquals(topic, subscription.topic());
                assertEquals(groupName, subscription.groupName());
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertNotNull(subscription.subscribedAt());
                assertNotNull(subscription.lastHeartbeatAt());
                logger.info("Subscribe with default options test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(30, subscription.heartbeatIntervalSeconds());
                assertEquals(120, subscription.heartbeatTimeoutSeconds());
                logger.info("Subscribe with custom options test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(pausedSubscription -> {
                assertEquals(SubscriptionState.PAUSED, pausedSubscription.state());
                assertFalse(pausedSubscription.isActive());
                return subscriptionManager.resume(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(resumedSubscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, resumedSubscription.state());
                assertTrue(resumedSubscription.isActive());
                logger.info("Pause and resume subscription test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(cancelledSubscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, cancelledSubscription.state());
                assertFalse(cancelledSubscription.isActive());
                logger.info("Cancel subscription test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(initial -> {
                initialHeartbeatRef.set(initial.lastHeartbeatAt());
                return manager.getVertx().timer(100);
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(updatedSubscription -> testContext.verify(() -> {
                assertTrue(updatedSubscription.lastHeartbeatAt().isAfter(initialHeartbeatRef.get()),
                    "Heartbeat timestamp should be updated");
                logger.info("Update heartbeat test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.subscribe(topic, "group-c", SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.listSubscriptions(topic))
            .onComplete(testContext.succeeding(subscriptions -> testContext.verify(() -> {
                assertEquals(3, subscriptions.size(), "Should have 3 subscriptions");
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-a")));
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-b")));
                assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-c")));
                logger.info("List subscriptions test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(paused -> {
                assertEquals(SubscriptionState.PAUSED, paused.state(), "Should be PAUSED");
                return subscriptionManager.updateHeartbeat(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(stillPaused -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, stillPaused.state(),
                    "PAUSED subscription should remain PAUSED after heartbeat");
                logger.info("Heartbeat keeps PAUSED subscription PAUSED test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
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
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning());
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
            .onComplete(testContext.succeeding(incrementedCount -> testContext.verify(() -> {
                assertTrue(incrementedCount > 0,
                    "Messages should have required_consumer_groups incremented to 2");
                logger.info("Subscribe FROM_BEGINNING auto-triggers backfill test passed");
                testContext.completeNow();
            })));
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
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.defaults());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(info -> testContext.verify(() -> {
                assertNotNull(info, "Subscription should exist");
                assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
                assertTrue(info.backfillStatus() == null || "NONE".equals(info.backfillStatus()),
                    "Backfill should NOT have been triggered for FROM_NOW, got: " + info.backfillStatus());
                logger.info("Subscribe FROM_NOW does not trigger backfill test passed");
                testContext.completeNow();
            })));
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
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(info -> testContext.verify(() -> {
                assertNotNull(info, "Subscription should exist");
                assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
                assertEquals(1L, info.startFromMessageId(), "start_from_message_id should be 1 for FROM_BEGINNING");
                logger.info("Subscribe FROM_BEGINNING without BackfillService test passed");
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeFromBeginningStartsNoBackfillWhenTheStoredBoundIsLater(VertxTestContext testContext) {
        String topic = "test-backfill-later-bound-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "later-bound-group";
        int messageCount = 3;

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
            .compose(v -> insertMessages(topic, messageCount))
            // The first subscription of the group stores a start bound after the three messages.
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
            .compose(v -> {
                subscriptionManager.setBackfillService(new BackfillService(connectionManager, "peegeeq-main"));
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(info -> {
                assertTrue(info.startFromMessageId() > 1,
                    "The stored start bound must stay after the existing messages, but was "
                        + info.startFromMessageId());
                assertEquals("NONE", info.backfillStatus(),
                    "No backfill must start: the stored start bound is not the beginning");
                return countMessagesWithRequiredGroups(topic, 1);
            })
            .onComplete(testContext.succeeding(requiredByInitialGroupOnly -> testContext.verify(() -> {
                assertEquals(messageCount, requiredByInitialGroupOnly,
                    "The messages before the stored start bound must not be given to the group");
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeFromBeginningStartsBackfillWhenTheStoredBoundIsTheBeginning(VertxTestContext testContext) {
        String topic = "test-backfill-stored-beginning-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "stored-beginning-group";
        int messageCount = 3;

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
            .compose(v -> insertMessages(topic, messageCount))
            // The first subscription of the group stores the beginning. No backfill service is set yet.
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()))
            .compose(v -> {
                subscriptionManager.setBackfillService(new BackfillService(connectionManager, "peegeeq-main"));
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(info -> testContext.verify(() -> {
                assertEquals(1L, info.startFromMessageId());
                assertEquals("COMPLETED", info.backfillStatus(),
                    "The backfill must run: the request and the stored start bound both name the beginning");
                assertEquals(messageCount, info.backfillProcessedMessages());
                testContext.completeNow();
            })));
    }

    // --- Helper methods ---

    private Future<Void> insertMessages(String topic, int count) {
        Future<Void> insertChain = Future.succeededFuture();
        for (int i = 0; i < count; i++) {
            final int index = i;
            insertChain = insertChain.compose(ignored ->
                insertMessage(topic, new JsonObject().put("index", index)).mapEmpty());
        }
        return insertChain;
    }

    /**
     * Inserts a test message into the outbox.
     */
    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withTransaction("peegeeq-main", connection -> {
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

