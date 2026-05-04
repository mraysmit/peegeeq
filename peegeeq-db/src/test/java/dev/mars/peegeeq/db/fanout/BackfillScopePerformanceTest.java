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

package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for {@link BackfillScope} behaviour in {@link BackfillService}.
 *
 * <p>Validates that both {@code PENDING_ONLY} and {@code ALL_RETAINED} scopes:
 * <ul>
 *   <li>Produce correct message counts and tracking rows at scale</li>
 *   <li>Maintain acceptable throughput (>= 1 000 msgs/s) for 50k+ messages</li>
 *   <li>{@code ALL_RETAINED} correctly increments {@code required_consumer_groups}
 *       on COMPLETED messages (regression guard for the incrementSql bug)</li>
 *   <li>Both scopes perform comparably ALL_RETAINED should not be dramatically slower</li>
 * </ul>
 *
 * <p>Classification: PERFORMANCE + INTEGRATION TEST</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-06
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Tag(TestCategories.INTEGRATION)
public class BackfillScopePerformanceTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillScopePerformanceTest.class);

    private final List<String> testTopics = new ArrayList<>();

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
                .maxSize(20)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");

        logger.info("BackfillScope performance test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            Future<Void> deleteFuture = testTopics.isEmpty()
                    ? Future.succeededFuture()
                    : connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(
                                "DELETE FROM outbox WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(testTopics.toArray(new String[0])))
                                .mapEmpty());
            deleteFuture
                    .compose(v -> connectionManager.close())
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Performance: PENDING_ONLY at scale
    // ========================================================================

    /**
     * Backfill 50k PENDING messages with PENDING_ONLY scope.
     * Establishes the baseline throughput for scope-filtered backfill.
     */
    @Test
    @Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    void testPendingOnlyScope_50kMessages_Throughput(VertxTestContext testContext) {
        String topic = "perf-pending-only-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "perf-pending-only-grp";
        int messageCount = 50_000;
        int batchSize = 10_000;

        logger.info("=== PENDING_ONLY scope performance: {} messages, batch {} ===", messageCount, batchSize);

        final long[] startMs = {0};
        setupTopicAndMessages(topic, messageCount)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    startMs[0] = System.currentTimeMillis();
                    return backfillService.startBackfill(topic, groupName, batchSize, 0, BackfillScope.PENDING_ONLY);
                })
                .compose(result -> {
                    long elapsedMs = System.currentTimeMillis() - startMs[0];
                    double throughput = messageCount * 1000.0 / elapsedMs;
                    testContext.verify(() -> {
                        assertEquals(BackfillResult.Status.COMPLETED, result.status());
                        assertEquals(messageCount, result.processedMessages());
                        assertTrue(throughput >= 1000,
                                "PENDING_ONLY throughput should be >= 1000 msgs/s, got " + String.format("%.1f", throughput));
                        logger.info("PENDING_ONLY result: {} msgs in {} ms ({} msgs/s)",
                                result.processedMessages(), elapsedMs, String.format("%.1f", throughput));
                    });
                    return countTrackingRows(topic, groupName);
                })
                .onSuccess(trackingRows -> testContext.verify(() -> {
                    assertEquals(messageCount, trackingRows.longValue(), "Should have one tracking row per message");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Performance: ALL_RETAINED at scale
    // ========================================================================

    /**
     * Backfill 50k messages (mix of PENDING + COMPLETED) with ALL_RETAINED scope.
     * Validates that COMPLETED messages are correctly included and incremented.
     */
    @Test
    @Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    void testAllRetainedScope_50kMessages_Throughput(VertxTestContext testContext) {
        String topic = "perf-all-retained-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "perf-all-retained-grp";
        int messageCount = 50_000;
        int completedCount = 20_000;
        int batchSize = 10_000;

        logger.info("=== ALL_RETAINED scope performance: {} total, {} completed, batch {} ===",
                messageCount, completedCount, batchSize);

        final long[] startMs = {0};
        setupTopicAndMessages(topic, messageCount)
                .compose(v -> markOldestMessagesCompleted(topic, completedCount))
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    startMs[0] = System.currentTimeMillis();
                    return backfillService.startBackfill(topic, groupName, batchSize, 0, BackfillScope.ALL_RETAINED);
                })
                .compose(result -> {
                    long elapsedMs = System.currentTimeMillis() - startMs[0];
                    double throughput = messageCount * 1000.0 / elapsedMs;
                    testContext.verify(() -> {
                        assertEquals(BackfillResult.Status.COMPLETED, result.status());
                        assertEquals(messageCount, result.processedMessages(),
                                "ALL_RETAINED should process ALL messages including COMPLETED");
                        assertTrue(throughput >= 1000,
                                "ALL_RETAINED throughput should be >= 1000 msgs/s, got " + String.format("%.1f", throughput));
                        logger.info("ALL_RETAINED result: {} msgs in {} ms ({} msgs/s)",
                                result.processedMessages(), elapsedMs, String.format("%.1f", throughput));
                    });
                    return countTrackingRows(topic, groupName);
                })
                .onSuccess(trackingRows -> testContext.verify(() -> {
                    assertEquals(messageCount, trackingRows.longValue(),
                            "Should have one tracking row per message for ALL_RETAINED");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Correctness: ALL_RETAINED increments required_consumer_groups on COMPLETED
    // ========================================================================

    /**
     * Regression test: verifies that ALL_RETAINED scope increments
     * {@code required_consumer_groups} on COMPLETED messages, not just PENDING ones.
     *
     * <p>This guards against the bug where {@code incrementSql} hardcoded
     * {@code status IN ('PENDING', 'PROCESSING')} regardless of scope.</p>
     */
    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testAllRetainedScope_IncrementsRequiredGroupsOnCompletedMessages(VertxTestContext testContext) {
        String topic = "perf-incr-completed-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "incr-completed-grp";
        int totalMessages = 100;
        int completedMessages = 40;

        logger.info("=== ALL_RETAINED incrementSql regression test: {} total, {} completed ===",
                totalMessages, completedMessages);

        setupTopicAndMessages(topic, totalMessages)
                .compose(v -> markOldestMessagesCompleted(topic, completedMessages))
                .compose(v -> countCompletedMessagesWithRequiredGroups(topic, 1))
                .compose(originalIncrCount -> {
                    testContext.verify(() ->
                            assertEquals(completedMessages, originalIncrCount.longValue(),
                                    "COMPLETED messages should start with required_consumer_groups = 1"));
                    return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning());
                })
                .compose(v -> backfillService.startBackfill(topic, groupName, 50, 0, BackfillScope.ALL_RETAINED))
                .compose(result -> {
                    testContext.verify(() -> {
                        assertEquals(BackfillResult.Status.COMPLETED, result.status());
                        assertEquals(totalMessages, result.processedMessages());
                    });
                    return countCompletedMessagesWithRequiredGroups(topic, 2);
                })
                .compose(incrementedCount -> {
                    testContext.verify(() ->
                            assertEquals(completedMessages, incrementedCount.longValue(),
                                    "ALL_RETAINED should increment required_consumer_groups on COMPLETED messages too"));
                    return countPendingMessagesWithRequiredGroups(topic, 2);
                })
                .onSuccess(pendingIncremented -> testContext.verify(() -> {
                    assertEquals(totalMessages - completedMessages, pendingIncremented.longValue(),
                            "PENDING messages should also have required_consumer_groups incremented");
                    logger.info("ALL_RETAINED correctly incremented required_consumer_groups on {} COMPLETED + {} PENDING messages",
                            completedMessages, pendingIncremented);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Comparative: PENDING_ONLY vs ALL_RETAINED throughput
    // ========================================================================

    /**
     * Side-by-side comparison of PENDING_ONLY vs ALL_RETAINED throughput on the
     * same dataset. ALL_RETAINED should not be more than 2x slower.
     */
    @Test
    @Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    void testScopeComparison_ThroughputParity(VertxTestContext testContext) {
        String baseTopic = "perf-compare-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 20_000;
        int completedCount = 8_000;
        int batchSize = 5_000;

        logger.info("=== Scope comparison: {} total, {} completed ===", messageCount, completedCount);

        String pendingTopic = baseTopic + "-pending";
        String pendingGroup = "compare-pending-grp";
        String retainedTopic = baseTopic + "-retained";
        String retainedGroup = "compare-retained-grp";

        final long[] pendingElapsed = {0};
        final long[] retainedElapsed = {0};
        final long[] pendingProcessed = {0};

        // --- PENDING_ONLY ---
        setupTopicAndMessages(pendingTopic, messageCount)
                .compose(v -> markOldestMessagesCompleted(pendingTopic, completedCount))
                .compose(v -> subscriptionManager.subscribe(pendingTopic, pendingGroup, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    long start = System.currentTimeMillis();
                    return backfillService.startBackfill(pendingTopic, pendingGroup, batchSize, 0, BackfillScope.PENDING_ONLY)
                            .map(result -> {
                                pendingElapsed[0] = System.currentTimeMillis() - start;
                                return result;
                            });
                })
                .compose(pendingResult -> {
                    int expectedPending = messageCount - completedCount;
                    testContext.verify(() -> {
                        assertEquals(BackfillResult.Status.COMPLETED, pendingResult.status());
                        assertEquals(expectedPending, pendingResult.processedMessages());
                    });
                    pendingProcessed[0] = pendingResult.processedMessages();
                    // --- ALL_RETAINED ---
                    return setupTopicAndMessages(retainedTopic, messageCount);
                })
                .compose(v -> markOldestMessagesCompleted(retainedTopic, completedCount))
                .compose(v -> subscriptionManager.subscribe(retainedTopic, retainedGroup, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    long start = System.currentTimeMillis();
                    return backfillService.startBackfill(retainedTopic, retainedGroup, batchSize, 0, BackfillScope.ALL_RETAINED)
                            .map(result -> {
                                retainedElapsed[0] = System.currentTimeMillis() - start;
                                return result;
                            });
                })
                .onSuccess(retainedResult -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, retainedResult.status());
                    assertEquals(messageCount, retainedResult.processedMessages());

                    double pendingThroughput = pendingProcessed[0] * 1000.0 / pendingElapsed[0];
                    double retainedThroughput = retainedResult.processedMessages() * 1000.0 / retainedElapsed[0];
                    double ratio = retainedThroughput / pendingThroughput;

                    logger.info("=== SCOPE COMPARISON RESULTS ===");
                    logger.info("PENDING_ONLY : {} msgs in {} ms ({} msgs/s)",
                            pendingProcessed[0], pendingElapsed[0], String.format("%.1f", pendingThroughput));
                    logger.info("ALL_RETAINED : {} msgs in {} ms ({} msgs/s)",
                            retainedResult.processedMessages(), retainedElapsed[0], String.format("%.1f", retainedThroughput));
                    logger.info("Throughput ratio (ALL_RETAINED / PENDING_ONLY): {}", String.format("%.2f", ratio));

                    assertTrue(ratio >= 0.5,
                            "ALL_RETAINED throughput should be at least 50% of PENDING_ONLY, ratio was " + String.format("%.2f", ratio));
                    logger.info("Scope throughput parity verified: ratio={}", String.format("%.2f", ratio));

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Future<Void> setupTopicAndMessages(String topic, int messageCount) {
        testTopics.add(topic);
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic,
                        "initial-group-" + UUID.randomUUID().toString().substring(0, 4),
                        SubscriptionOptions.defaults()))
                .compose(v -> insertMessagesBulk(topic, messageCount));
    }

    private Future<Void> insertMessagesBulk(String topic, int count) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                SELECT $1, ('{"index": ' || generate_series || '}')::jsonb, $2, 'PENDING'
                FROM generate_series(1, $3)
                """;

            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, OffsetDateTime.now(ZoneOffset.UTC), count))
                    .mapEmpty();
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

    private Future<Long> countTrackingRows(String topic, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt
                FROM outbox o
                JOIN outbox_consumer_groups cg ON o.id = cg.message_id
                WHERE o.topic = $1 AND cg.group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }

    private Future<Long> countCompletedMessagesWithRequiredGroups(String topic, int requiredGroups) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox
                WHERE topic = $1 AND status = 'COMPLETED' AND required_consumer_groups = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, requiredGroups))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }

    private Future<Long> countPendingMessagesWithRequiredGroups(String topic, int requiredGroups) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox
                WHERE topic = $1 AND status IN ('PENDING', 'PROCESSING') AND required_consumer_groups = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, requiredGroups))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }
}
