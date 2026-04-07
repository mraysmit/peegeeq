package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillProgress;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackfillService}.
 *
 * <p>Tests validate the complete backfill lifecycle including:
 * starting, batched processing, checkpoint-based resumability,
 * cancellation, and idempotent completion.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class BackfillServiceIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillServiceIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;

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
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    /**
     * Test backfill of a small number of messages end-to-end.
     */
    @Test
    void testBackfillSmallBatch(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-small-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-group-1";
        int messageCount = 5;

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, messageCount))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, 100, 0))
                .compose(result -> {
                    testContext.verify(() -> {
                        assertEquals(BackfillResult.Status.COMPLETED, result.status(),
                                "Backfill should complete successfully");
                        assertEquals(messageCount, result.processedMessages(),
                                "Should process all " + messageCount + " messages");
                    });
                    return backfillService.getBackfillProgress(topic, groupName);
                })
                .compose(optProgress -> {
                    BackfillProgress progress = optProgress.orElseThrow();
                    testContext.verify(() -> {
                        assertEquals("COMPLETED", progress.status());
                        assertEquals(messageCount, progress.processedMessages());
                        assertNotNull(progress.completedAt());
                    });
                    return countMessagesWithRequiredGroups(topic, 2);
                })
                .onSuccess(incrementedCount -> testContext.verify(() -> {
                    assertTrue(incrementedCount > 0,
                            "At least some messages should have required_consumer_groups incremented to 2");
                    logger.info("Small batch backfill verified: {} messages processed", messageCount);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test backfill with multiple batches (batch size < total messages).
     */
    @Test
    void testBackfillMultipleBatches(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-multi-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-batch-group";
        int messageCount = 25;

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, messageCount))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, 10, 0))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertEquals(messageCount, result.processedMessages(),
                            "Should process all messages across multiple batches");
                    logger.info("Multi-batch backfill verified: {} messages in batches of 10", result.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test backfill with max messages limit.
     */
    @Test
    void testBackfillWithMaxLimit(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-max-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-max-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, 20))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, 5, 10))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertEquals(10, result.processedMessages(),
                            "Should stop after processing maxMessages (10)");
                    logger.info("Max-limited backfill verified: processed {} of 20", result.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that backfill of an already-completed subscription returns immediately.
     */
    @Test
    void testBackfillAlreadyCompleted(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-done-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-done-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", 1)).mapEmpty())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName))
                .compose(firstResult -> backfillService.startBackfill(topic, groupName))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.ALREADY_COMPLETED, result.status(),
                            "Second backfill should return ALREADY_COMPLETED");
                    logger.info("Idempotent backfill verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test backfill with no messages to process.
     */
    @Test
    void testBackfillNoMessages(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-empty-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-empty-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertEquals(0, result.processedMessages(), "Should process 0 messages");
                    logger.info("Empty backfill verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test backfill cancellation.
     */
    @Test
    void testBackfillCancellation(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-cancel-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, 10))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> connectionManager.withConnection("peegeeq-main", connection -> {
                    String sql = """
                        UPDATE outbox_topic_subscriptions
                        SET backfill_status = 'IN_PROGRESS'
                        WHERE topic = $1 AND group_name = $2
                        """;
                    return connection.preparedQuery(sql)
                            .execute(Tuple.of(topic, groupName))
                            .mapEmpty();
                }))
                .compose(v -> backfillService.cancelBackfill(topic, groupName))
                .compose(v -> backfillService.getBackfillProgress(topic, groupName))
                .onSuccess(optProgress -> testContext.verify(() -> {
                    BackfillProgress progress = optProgress.orElseThrow();
                    assertEquals("CANCELLED", progress.status(),
                            "Backfill should be cancelled");
                    logger.info("Backfill cancellation verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that backfill fails for non-ACTIVE subscriptions.
     */
    @Test
    void testBackfillFailsForNonActiveSubscription(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-paused-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-paused-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> subscriptionManager.pause(topic, groupName))
                .compose(v -> backfillService.startBackfill(topic, groupName))
                .onSuccess(result -> testContext.failNow("Backfill should fail for PAUSED subscription"))
                .onFailure(err -> testContext.verify(() -> {
                    assertTrue(err.getMessage().contains("ACTIVE"),
                            "Error should mention ACTIVE requirement");
                    logger.info("Backfill validation for non-ACTIVE subscription verified");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that backfill fails for non-existent subscription.
     */
    @Test
    void testBackfillFailsForMissingSubscription(VertxTestContext testContext) throws InterruptedException {
        backfillService.startBackfill("nonexistent-topic", "nonexistent-group")
                .onSuccess(result -> testContext.failNow("Backfill should fail for non-existent subscription"))
                .onFailure(err -> testContext.verify(() -> {
                    assertTrue(err.getMessage().contains("not found"),
                            "Error should mention subscription not found");
                    logger.info("Backfill validation for missing subscription verified");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test backfill progress tracking.
     */
    @Test
    void testBackfillProgressTracking(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-progress-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-progress-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, 15))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.getBackfillProgress(topic, groupName))
                .compose(beforeOpt -> {
                    BackfillProgress before = beforeOpt.orElseThrow();
                    testContext.verify(() -> assertEquals("NONE", before.status()));
                    return backfillService.startBackfill(topic, groupName, 5, 0);
                })
                .compose(result -> backfillService.getBackfillProgress(topic, groupName))
                .onSuccess(afterOpt -> testContext.verify(() -> {
                    BackfillProgress after = afterOpt.orElseThrow();
                    assertEquals("COMPLETED", after.status());
                    assertEquals(15, after.processedMessages());
                    assertNotNull(after.startedAt());
                    assertNotNull(after.completedAt());
                    assertEquals(100.0, after.percentComplete(), 0.1);
                    logger.info("Backfill progress tracking verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that backfill scope changes which message statuses are included.
     */
    @Test
    void testBackfillScopePendingOnlyVsAllRetained(VertxTestContext testContext) throws InterruptedException {
        String topic = "test-backfill-scope-" + UUID.randomUUID().toString().substring(0, 8);
        String pendingOnlyGroup = "scope-pending-only";
        String allRetainedGroup = "scope-all-retained";
        int totalMessages = 10;
        int completedMessages = 4;

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, totalMessages))
                .compose(v -> markOldestMessagesCompleted(topic, completedMessages))
                .compose(v -> subscriptionManager.subscribe(topic, pendingOnlyGroup,
                        SubscriptionOptions.fromBeginning(BackfillScope.PENDING_ONLY)))
                .compose(v -> subscriptionManager.subscribe(topic, allRetainedGroup,
                        SubscriptionOptions.fromBeginning(BackfillScope.ALL_RETAINED)))
                .compose(v -> backfillService.startBackfill(topic, pendingOnlyGroup, 100, 0, BackfillScope.PENDING_ONLY))
                .compose(pendingOnlyResult -> {
                    testContext.verify(() -> {
                        int expectedPendingOnly = totalMessages - completedMessages;
                        assertEquals(BackfillResult.Status.COMPLETED, pendingOnlyResult.status());
                        assertEquals(expectedPendingOnly, pendingOnlyResult.processedMessages(),
                                "PENDING_ONLY should exclude COMPLETED rows");
                    });
                    return backfillService.startBackfill(topic, allRetainedGroup, 100, 0, BackfillScope.ALL_RETAINED)
                            .map(allRetainedResult -> new BackfillResult[]{pendingOnlyResult, allRetainedResult});
                })
                .onSuccess(results -> testContext.verify(() -> {
                    BackfillResult pendingOnlyResult = results[0];
                    BackfillResult allRetainedResult = results[1];
                    assertEquals(BackfillResult.Status.COMPLETED, allRetainedResult.status());
                    assertEquals(totalMessages, allRetainedResult.processedMessages(),
                            "ALL_RETAINED should include COMPLETED rows");
                    assertTrue(allRetainedResult.processedMessages() > pendingOnlyResult.processedMessages(),
                            "ALL_RETAINED should process more messages than PENDING_ONLY on mixed-status data");
                    logger.info("Backfill scope verified: PENDING_ONLY={}, ALL_RETAINED={}",
                            pendingOnlyResult.processedMessages(), allRetainedResult.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // Helper methods

    private Future<Void> insertMessages(String topic, int count) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < count; i++) {
            final int index = i;
            chain = chain.compose(v -> insertMessage(topic, new JsonObject().put("index", index)).mapEmpty());
        }
        return chain;
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

        private Future<Void> markOldestMessagesCompleted(String topic, int limit) {
                return connectionManager.withConnection("peegeeq-main", connection -> {
                        String sql = """
                                WITH to_complete AS (
                                        SELECT id
                                        FROM outbox
                                        WHERE topic = $1 AND status = 'PENDING'
                                        ORDER BY id ASC
                                        LIMIT $2
                                )
                                UPDATE outbox o
                                SET status = 'COMPLETED'
                                FROM to_complete tc
                                WHERE o.id = tc.id
                                """;

                        return connection.preparedQuery(sql)
                                        .execute(Tuple.of(topic, limit))
                                        .mapEmpty();
                });
        }
}
