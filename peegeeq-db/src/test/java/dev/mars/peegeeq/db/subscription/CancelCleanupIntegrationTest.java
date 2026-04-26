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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Task H6: CANCELLED Subscription Orphan Cleanup.
 *
 * <p>When a subscription is cancelled via {@code cancel()}, its PENDING/PROCESSING
 * rows in {@code outbox_consumer_groups} should be removed and
 * {@code required_consumer_groups} decremented so messages are not left blocked.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-07
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class CancelCleanupIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CancelCleanupIntegrationTest.class);

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

        logger.info("CancelCleanupIntegrationTest setup complete");
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
    void testCancelCleansUpOrphanedRowsAndDecrementsRequiredGroups(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing cancel() cleans up messages ===");

        String topic = "test-cancel-cleanup-" + UUID.randomUUID().toString().substring(0, 8);
        String groupA = "group-a";
        String groupB = "group-b";
        int messageCount = 5;

        AtomicReference<List<Long>> messageIdsRef = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupA, SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.subscribe(topic, groupB, SubscriptionOptions.defaults()))
            .compose(v -> {
                DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
                subscriptionManager.setDeadConsumerGroupCleanup(cleanup);
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx))
                            .onSuccess(id -> messageIdsRef.get().add(id))
                            .mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> countMessagesWithRequiredGroups(topic, 2))
            .compose(twoGroupMsgs -> {
                assertEquals(messageCount, twoGroupMsgs, "Messages should require 2 consumer groups");
                Future<Void> insertGroupChain = Future.succeededFuture();
                for (long msgId : messageIdsRef.get()) {
                    insertGroupChain = insertGroupChain.compose(ignored ->
                        insertConsumerGroupRow(msgId, groupB, "PENDING"));
                }
                return insertGroupChain;
            })
            .compose(v -> countConsumerGroupRows(topic, groupB))
            .compose(groupBRows -> {
                assertEquals(messageCount, groupBRows, "groupB should have tracking rows in outbox_consumer_groups");
                return subscriptionManager.cancel(topic, groupB);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupB))
            .compose(cancelled -> {
                assertEquals(SubscriptionState.CANCELLED, cancelled.state());
                return countMessagesWithRequiredGroups(topic, 1);
            })
            .compose(oneGroupMsgs -> {
                assertEquals(messageCount, oneGroupMsgs,
                    "After cancel, messages should require 1 consumer group (decremented from 2)");
                return countConsumerGroupRows(topic, groupB);
            })
            .onSuccess(groupBRowsAfter -> {
                try {
                    assertEquals(0, groupBRowsAfter,
                        "Cancelled group's outbox_consumer_groups rows should be removed");
                    logger.info("Cancel cleanup test passed");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail(errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testCancelWithoutCleanupServiceStillSucceeds(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing cancel without DeadConsumerGroupCleanup still works ===");

        String topic = "test-cancel-nocleanup-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-cleanup-group";

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onSuccess(cancelled -> {
                try {
                    assertEquals(SubscriptionState.CANCELLED, cancelled.state(),
                        "Cancel should succeed even without cleanup service configured");
                    logger.info("Cancel without cleanup service test passed");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail(errorRef.get().getMessage(), errorRef.get());
    }

    /**
     * Verifies that a failure in {@code DeadConsumerGroupCleanup} propagates out of {@code cancel()}.
     *
     * <p>Cleanup failure is data-integrity critical — it means orphan rows were NOT removed and
     * {@code required_consumer_groups} was NOT decremented. Silently swallowing the error would
     * leave the topic in a corrupt state. The caller must be informed so it can retry or alert.</p>
     *
     * <p>A broken {@link PgConnectionManager} with no registered pool is injected so that
     * {@code cleanupDeadGroup} fails with "No reactive pool found for service: no-such-pool".
     * The test asserts that this failure propagates as an {@link java.util.concurrent.ExecutionException}
     * rather than being silently swallowed.</p>
     */
    @Test
    void testCancelCleanupFailurePropagates(VertxTestContext testContext) throws Exception {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Cancel cleanup failed') is EXPECTED — this test deliberately uses a broken connection manager to verify cleanup failure propagates from cancel");
        logger.info("=== Testing cancel cleanup failure propagates ===");

        String topic = "test-cancel-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "cancel-fail-group";

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> {
                PgConnectionManager brokenConnectionManager = new PgConnectionManager(manager.getVertx(), null);
                DeadConsumerGroupCleanup brokenCleanup = new DeadConsumerGroupCleanup(brokenConnectionManager, "no-such-pool");
                subscriptionManager.setDeadConsumerGroupCleanup(brokenCleanup);
                return subscriptionManager.cancel(topic, groupName);
            })
            .transform(ar -> {
                if (ar.succeeded()) {
                    return Future.failedFuture(new AssertionError(
                        "Cancel must fail when cleanup fails — failure must propagate"));
                }
                try {
                    assertNotNull(ar.cause().getMessage());
                    logger.info("Cancel cleanup failure propagation test passed");
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail(errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testForceRemoveBehaviourUnchanged(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing forceRemoveConsumerGroup still works correctly ===");

        String topic = "test-forcerm-unchanged-" + UUID.randomUUID().toString().substring(0, 8);
        String groupA = "group-a";
        String groupB = "group-b";
        int messageCount = 3;

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupA, SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.subscribe(topic, groupB, SubscriptionOptions.defaults()))
            .compose(v -> {
                DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
                subscriptionManager.setDeadConsumerGroupCleanup(cleanup);
                Future<Void> insertChain = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    insertChain = insertChain.compose(ignored ->
                        insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return insertChain;
            })
            .compose(v -> subscriptionManager.forceRemoveConsumerGroup(topic, groupB))
            .compose(v -> subscriptionManager.getSubscription(topic, groupB))
            .compose(removed -> {
                assertEquals(SubscriptionState.CANCELLED, removed.state());
                return countMessagesWithRequiredGroups(topic, 1);
            })
            .onSuccess(oneGroupMsgs -> {
                try {
                    assertEquals(messageCount, oneGroupMsgs,
                        "forceRemove should still decrement required_consumer_groups");
                    logger.info("forceRemove behaviour unchanged test passed");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail(errorRef.get().getMessage(), errorRef.get());
    }

    // --- Helper methods ---

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

    private Future<Long> countConsumerGroupRows(String topic, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox_consumer_groups ocg
                JOIN outbox o ON o.id = ocg.message_id
                WHERE o.topic = $1 AND ocg.group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }

    private Future<Void> insertConsumerGroupRow(long messageId, String groupName, String status) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                VALUES ($1, $2, $3)
                ON CONFLICT (message_id, group_name) DO NOTHING
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName, status))
                    .mapEmpty();
        });
    }
}
