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
 *   <li>Resurrection triggers backfill — messages cleaned up during DEAD period are re-backfilled</li>
 *   <li>Resurrection without BackfillService — heartbeat succeeds, no backfill, no error</li>
 *   <li>Resurrection backfill failure — heartbeat still succeeds</li>
 *   <li>ACTIVE heartbeat does NOT trigger backfill — only DEAD→ACTIVE transitions</li>
 *   <li>PAUSED heartbeat does NOT trigger backfill — PAUSED stays PAUSED</li>
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
    void setUp() throws Exception {
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
    void tearDown() throws Exception {
        if (connectionManager != null) {
            awaitFuture(connectionManager.close());
        }
    }

    @Test
    void testResurrectionTriggersBackfillForCleanedUpMessages() throws Exception {
        logger.info("=== Testing DEAD→ACTIVE resurrection triggers re-backfill ===");

        String topic = "test-resurrect-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String lateGroup = "late-joiner";

        // 1. Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // 2. Subscribe the initial group so messages get required_consumer_groups = 1
        subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // 3. Configure BackfillService + DeadConsumerGroupCleanup
        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);
        DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
        subscriptionManager.setDeadConsumerGroupCleanup(cleanup);

        // 4. Subscribe late-joiner with FROM_BEGINNING (creates subscription + backfills existing)
        subscriptionManager.subscribe(topic, lateGroup, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();

        // 5. Insert messages after late-joiner is active — these go to both groups
        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // Verify messages have required_consumer_groups = 2
        long twoGroupMsgs = countMessagesWithRequiredGroups(topic, 2)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, twoGroupMsgs, "All messages should require 2 consumer groups");

        // 6. Mark late-joiner as DEAD and clean up (simulates what detection job does)
        setSubscriptionStatus(topic, lateGroup, "DEAD");
        cleanup.cleanupDeadGroup(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();

        // Verify cleanup decremented: messages now require 1 group
        long oneGroupMsgs = countMessagesWithRequiredGroups(topic, 1)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, oneGroupMsgs, "After cleanup, messages should require 1 consumer group");

        // 7. Resurrect via heartbeat — should trigger backfill
        subscriptionManager.updateHeartbeat(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();

        // 8. Verify resurrected to ACTIVE
        SubscriptionInfo resurrected = subscriptionManager.getSubscription(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.ACTIVE, resurrected.state(),
                    "Should be ACTIVE after heartbeat");

        // 9. Verify backfill ran — messages should have required_consumer_groups incremented back to 2
        long rebackfilledMsgs = countMessagesWithRequiredGroups(topic, 2)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, rebackfilledMsgs,
                    "After resurrection re-backfill, messages should require 2 consumer groups again");

        // 10. Verify backfill status is COMPLETED
        assertEquals("COMPLETED", resurrected.backfillStatus(),
                    "Backfill should complete after resurrection");

        logger.info("✅ Resurrection triggers backfill test passed");
    }

    @Test
    void testResurrectionWithoutBackfillServiceStillSucceeds() throws Exception {
        logger.info("=== Testing resurrection without BackfillService still works ===");

        String topic = "test-resurrect-nosvc-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-backfill-svc-group";

        // Create topic and subscribe
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Do NOT set BackfillService on the SubscriptionManager

        // Mark as DEAD
        setSubscriptionStatus(topic, groupName, "DEAD");

        // Resurrect via heartbeat — should succeed even without BackfillService
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        // Verify resurrected to ACTIVE
        SubscriptionInfo resurrected = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.ACTIVE, resurrected.state(),
                    "Should be ACTIVE after heartbeat, even without BackfillService");

        logger.info("✅ Resurrection without BackfillService test passed");
    }

    @Test
    void testResurrectionResetsBackfillStatusBeforeReBackfill() throws Exception {
        logger.info("=== Testing resurrection resets backfill status before triggering re-backfill ===");

        String topic = "test-resurrect-reset-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String lateGroup = "late-reset-group";

        // Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // Subscribe initial group
        subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Configure BackfillService
        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);
        DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
        subscriptionManager.setDeadConsumerGroupCleanup(cleanup);

        // Subscribe late-joiner with FROM_BEGINNING (creates subscription + initial backfill)
        subscriptionManager.subscribe(topic, lateGroup, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();

        // Verify initial backfill completed
        SubscriptionInfo afterSubscribe = subscriptionManager.getSubscription(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals("COMPLETED", afterSubscribe.backfillStatus(),
                    "Initial backfill should have completed");

        // Insert messages AFTER initial backfill
        int messageCount = 3;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // Mark as DEAD and cleanup
        setSubscriptionStatus(topic, lateGroup, "DEAD");
        cleanup.cleanupDeadGroup(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();

        // At this point backfill_status is still 'COMPLETED' from the initial subscription.
        // Without resetBackfillStatus, the re-backfill would return ALREADY_COMPLETED and
        // the messages would never get their required_consumer_groups re-incremented.

        // Resurrect via heartbeat
        subscriptionManager.updateHeartbeat(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();

        // The key assertion: backfill must have processed the messages (not skipped as ALREADY_COMPLETED)
        SubscriptionInfo resurrected = subscriptionManager.getSubscription(topic, lateGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.ACTIVE, resurrected.state());
        assertEquals("COMPLETED", resurrected.backfillStatus(),
                    "Re-backfill should complete fresh after resurrection");
        assertTrue(resurrected.backfillProcessedMessages() >= messageCount,
                  "Re-backfill must process messages (not skip as ALREADY_COMPLETED). " +
                  "Processed: " + resurrected.backfillProcessedMessages());

        logger.info("✅ Resurrection resets backfill status test passed");
    }

    @Test
    void testResurrectionBackfillFailureDoesNotFailHeartbeat() throws Exception {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Resurrection re-backfill failed') is EXPECTED — this test deliberately uses a broken connection manager to verify backfill failure does not fail heartbeat");
        logger.info("=== Testing resurrection backfill failure doesn't fail heartbeat ===");

        String topic = "test-resurrect-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-fail-group";

        // Create topic and subscribe
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Create a BackfillService with a BROKEN connection manager (no pool registered).
        // When startBackfill tries to use it, withConnection returns failedFuture.
        // This exercises the .transform() fallback path in updateHeartbeat.
        PgConnectionManager brokenConnectionManager = new PgConnectionManager(manager.getVertx(), null);
        // Intentionally do NOT register any pool on brokenConnectionManager
        BackfillService brokenBackfillService = new BackfillService(brokenConnectionManager, "no-such-pool");
        subscriptionManager.setBackfillService(brokenBackfillService);

        // Mark as DEAD
        setSubscriptionStatus(topic, groupName, "DEAD");

        // Resurrect via heartbeat — backfill will fail ("No reactive pool found"),
        // but .transform() should catch it and heartbeat still succeeds
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        // Verify resurrected to ACTIVE despite backfill failure
        SubscriptionInfo resurrected = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.ACTIVE, resurrected.state(),
                    "Heartbeat must succeed even when backfill fails");

        // backfill_status should be NULL (resetBackfillStatus cleared it, but backfill failed before completing)
        assertNull(resurrected.backfillStatus(),
                  "Backfill status should be NULL since backfill failed after reset");

        logger.info("✅ Resurrection backfill failure resilience test passed");
    }

    @Test
    void testActiveHeartbeatDoesNotTriggerBackfill() throws Exception {
        logger.info("=== Testing ACTIVE heartbeat does NOT trigger backfill ===");

        String topic = "test-active-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String producerGroup = "producer-group";
        String activeGroup = "active-group";

        // Setup topic with two groups
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, producerGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);

        subscriptionManager.subscribe(topic, activeGroup, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();

        // Insert messages
        int messageCount = 3;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // Record current backfill state
        SubscriptionInfo before = subscriptionManager.getSubscription(topic, activeGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.ACTIVE, before.state());
        String backfillStatusBefore = before.backfillStatus();

        // Send heartbeat on ACTIVE subscription
        subscriptionManager.updateHeartbeat(topic, activeGroup)
            .toCompletionStage().toCompletableFuture().get();

        // Verify messages still require 2 groups (not 3 — no extra backfill ran)
        long twoGroupMsgs = countMessagesWithRequiredGroups(topic, 2)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, twoGroupMsgs,
                    "Messages should still require 2 groups — ACTIVE heartbeat should not trigger backfill");

        // Verify backfill status unchanged
        SubscriptionInfo after = subscriptionManager.getSubscription(topic, activeGroup)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(backfillStatusBefore, after.backfillStatus(),
                    "Backfill status should be unchanged after ACTIVE heartbeat");

        logger.info("✅ ACTIVE heartbeat does not trigger backfill test passed");
    }

    @Test
    void testPausedHeartbeatDoesNotTriggerBackfill() throws Exception {
        logger.info("=== Testing PAUSED heartbeat does NOT trigger backfill ===");

        String topic = "test-paused-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "paused-group";

        // Setup topic and subscribe
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Pause the subscription
        subscriptionManager.pause(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo paused = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.PAUSED, paused.state(), "Should be PAUSED");

        // Send heartbeat — PAUSED stays PAUSED (no resurrection, no backfill)
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        // Verify still PAUSED
        SubscriptionInfo afterHeartbeat = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.PAUSED, afterHeartbeat.state(),
                    "PAUSED subscription should stay PAUSED after heartbeat");

        logger.info("✅ PAUSED heartbeat does not trigger backfill test passed");
    }

    // --- Helper methods ---

    private void setSubscriptionStatus(String topic, String groupName, String status) throws Exception {
        connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $1
                WHERE topic = $2 AND group_name = $3
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(status, topic, groupName))
                .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
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
