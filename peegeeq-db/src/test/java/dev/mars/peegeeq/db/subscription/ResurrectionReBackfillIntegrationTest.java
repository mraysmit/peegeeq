package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Task H5: Resurrection Re-Backfill.
 *
 * <p>When a DEAD consumer group is resurrected via heartbeat (DEAD→ACTIVE),
 * messages that were cleaned up during the DEAD period should be re-backfilled
 * so the consumer group can process them.</p>
 *
 * <p>Test plan:</p>
 * <ol>
 *   <li>Resurrection triggers backfill messages cleaned up during DEAD period are re-backfilled</li>
 *   <li>Resurrection without BackfillService heartbeat succeeds, no backfill, no error</li>
 *   <li>Resurrection backfill failure heartbeat still succeeds</li>
 *   <li>ACTIVE heartbeat does NOT trigger backfill only DEAD→ACTIVE transitions</li>
 *   <li>PAUSED heartbeat does NOT trigger backfill PAUSED stays PAUSED</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-07
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class ResurrectionReBackfillIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ResurrectionReBackfillIntegrationTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

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

        logger.info("ResurrectionReBackfillIntegrationTest setup complete");
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
    void testResurrectionTriggersBackfillForCleanedUpMessages(VertxTestContext testContext) {
        logger.info("=== Testing DEAD→ACTIVE resurrection triggers re-backfill ===");

        String topic = "test-resurrect-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String lateGroup = "late-joiner";
        int messageCount = 5;

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults()))
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
                subscriptionManager.setDeadConsumerGroupCleanup(cleanup);
                return subscriptionManager.subscribe(topic, lateGroup, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> {
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> countMessagesWithRequiredGroups(topic, 2))
            .compose(twoGroupMsgs -> {
                assertEquals(messageCount, twoGroupMsgs, "All messages should require 2 consumer groups");
                return setSubscriptionStatus(topic, lateGroup, "DEAD");
            })
            .compose(v -> new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main")
                .cleanupDeadGroup(topic, lateGroup).mapEmpty())
            .compose(v -> countMessagesWithRequiredGroups(topic, 1))
            .compose(oneGroupMsgs -> {
                assertEquals(messageCount, oneGroupMsgs, "After cleanup, messages should require 1 consumer group");
                return subscriptionManager.updateHeartbeat(topic, lateGroup);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, lateGroup))
            .compose(resurrected -> {
                assertEquals(SubscriptionState.ACTIVE, resurrected.state(), "Should be ACTIVE after heartbeat");
                assertEquals("COMPLETED", resurrected.backfillStatus(), "Backfill should complete after resurrection");
                return countMessagesWithRequiredGroups(topic, 2);
            })
            .onComplete(testContext.succeeding(rebackfilledMsgs -> testContext.verify(() -> {
                assertEquals(messageCount, rebackfilledMsgs,
                    "After resurrection re-backfill, messages should require 2 consumer groups again");
                logger.info("Resurrection triggers backfill test passed");
                testContext.completeNow();
            })));
    }

    @Test
    void testResurrectionWithoutBackfillServiceStillSucceeds(VertxTestContext testContext) {
        logger.info("=== Testing resurrection without BackfillService still works ===");

        String topic = "test-resurrect-nosvc-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-backfill-svc-group";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> setSubscriptionStatus(topic, groupName, "DEAD"))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(resurrected -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, resurrected.state(),
                    "Should be ACTIVE after heartbeat, even without BackfillService");
                logger.info("Resurrection without BackfillService test passed");
                testContext.completeNow();
            })));
    }

    @Test
    void testResurrectionResetsBackfillStatusBeforeReBackfill(VertxTestContext testContext) {
        logger.info("=== Testing resurrection resets backfill status before triggering re-backfill ===");

        String topic = "test-resurrect-reset-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String lateGroup = "late-reset-group";
        int messageCount = 3;

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults()))
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
                subscriptionManager.setDeadConsumerGroupCleanup(cleanup);
                return subscriptionManager.subscribe(topic, lateGroup, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, lateGroup))
            .compose(afterSubscribe -> {
                assertEquals("COMPLETED", afterSubscribe.backfillStatus(),
                    "Initial backfill should have completed");
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> setSubscriptionStatus(topic, lateGroup, "DEAD"))
            .compose(v -> new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main")
                .cleanupDeadGroup(topic, lateGroup).mapEmpty())
            .compose(v -> subscriptionManager.updateHeartbeat(topic, lateGroup))
            .compose(v -> subscriptionManager.getSubscription(topic, lateGroup))
            .onComplete(testContext.succeeding(resurrected -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, resurrected.state());
                assertEquals("COMPLETED", resurrected.backfillStatus(),
                    "Re-backfill should complete fresh after resurrection");
                assertTrue(resurrected.backfillProcessedMessages() >= messageCount,
                    "Re-backfill must process messages (not skip as ALREADY_COMPLETED). " +
                    "Processed: " + resurrected.backfillProcessedMessages());
                logger.info("Resurrection resets backfill status test passed");
                testContext.completeNow();
            })));
    }

    /**
     * Verifies that a failure in the resurrection re-backfill propagates out of {@code updateHeartbeat()}.
     *
     * <p>When a DEAD subscription is resurrected (DEAD→ACTIVE) and the subsequent re-backfill fails,
     * the consumer group will miss messages that were cleaned up during the DEAD period. Silently
     * swallowing the error would leave the consumer in an inconsistent state with no indication that
     * messages were lost. The caller must be informed so it can retry or alert.</p>
     *
     * <p>A broken {@link BackfillService} backed by a {@link PgConnectionManager} with no registered
     * pool is injected so that {@code startBackfill} fails with
     * "No reactive pool found for service: no-such-pool". The subscription is manually set to DEAD
     * before the heartbeat so that the resurrection path is exercised. The test asserts that the
     * backfill failure propagates as an {@link java.util.concurrent.ExecutionException} rather than
     * being silently swallowed.</p>
     */
    @Test
    void testResurrectionBackfillFailurePropagates(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Resurrection re-backfill failed') is EXPECTED this test deliberately uses a broken connection manager to verify backfill failure propagates from heartbeat");
        logger.info("=== Testing resurrection backfill failure propagates ===");

        String topic = "test-resurrect-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-fail-group";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> {
                PgConnectionManager brokenConnectionManager = new PgConnectionManager(manager.getVertx(), null);
                BackfillService brokenBackfillService = new BackfillService(brokenConnectionManager, "no-such-pool");
                subscriptionManager.setBackfillService(brokenBackfillService);
                return setSubscriptionStatus(topic, groupName, "DEAD");
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .transform(ar -> {
                if (ar.succeeded()) {
                    return Future.failedFuture(new AssertionError(
                        "Heartbeat must fail when backfill fails failure must propagate"));
                }
                try {
                    assertNotNull(ar.cause().getMessage());
                    logger.info("Resurrection backfill failure propagation test passed");
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testActiveHeartbeatDoesNotTriggerBackfill(VertxTestContext testContext) {
        logger.info("=== Testing ACTIVE heartbeat does NOT trigger backfill ===");

        String topic = "test-active-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String activeGroup = "active-group";
        int messageCount = 3;

        AtomicReference<String> backfillStatusRef = new AtomicReference<>();
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults()))
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                return subscriptionManager.subscribe(topic, activeGroup, SubscriptionOptions.fromBeginning());
            })
            .compose(v -> {
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> subscriptionManager.getSubscription(topic, activeGroup))
            .compose(before -> {
                assertEquals(SubscriptionState.ACTIVE, before.state());
                backfillStatusRef.set(before.backfillStatus());
                return subscriptionManager.updateHeartbeat(topic, activeGroup);
            })
            .compose(v -> countMessagesWithRequiredGroups(topic, 2))
            .compose(twoGroupMsgs -> {
                assertEquals(messageCount, twoGroupMsgs,
                    "Messages should still require 2 groups ACTIVE heartbeat should not trigger backfill");
                return subscriptionManager.getSubscription(topic, activeGroup);
            })
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(backfillStatusRef.get(), after.backfillStatus(),
                    "Backfill status should be unchanged after ACTIVE heartbeat");
                logger.info("ACTIVE heartbeat does not trigger backfill test passed");
                testContext.completeNow();
            })));
    }

    @Test
    void testPausedHeartbeatDoesNotTriggerBackfill(VertxTestContext testContext) {
        logger.info("=== Testing PAUSED heartbeat does NOT trigger backfill ===");

        String topic = "test-paused-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "paused-group";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> {
                BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
                subscriptionManager.setBackfillService(backfillService);
                return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults());
            })
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(paused -> {
                assertEquals(SubscriptionState.PAUSED, paused.state(), "Should be PAUSED");
                return subscriptionManager.updateHeartbeat(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(afterHeartbeat -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, afterHeartbeat.state(),
                    "PAUSED subscription should stay PAUSED after heartbeat");
                logger.info("PAUSED heartbeat does not trigger backfill test passed");
                testContext.completeNow();
            })));
    }

    // --- Helper methods ---

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
