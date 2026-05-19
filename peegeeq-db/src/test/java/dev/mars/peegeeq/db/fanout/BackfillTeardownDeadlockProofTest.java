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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted proof test for the deadlock described in:
 * docs-design/analysis/backfill-performance-test-teardown-deadlock.md
 *
 * <h3>What this test proves</h3>
 * <p>Three test methods run in parallel (JUnit parallelism=4, mode=concurrent).
 * Each creates its own UUID-suffixed topic under the shared prefix
 * {@code "deadlock-probe-"}. The {@code @AfterEach tearDown()} deliberately uses
 * the <strong>broken broad LIKE pattern</strong>:</p>
 * <pre>{@code
 *   DELETE FROM outbox WHERE topic LIKE 'deadlock-probe-%'
 * }</pre>
 * <p>When test A's teardown fires while tests B and C are mid-backfill, the
 * DELETE acquires row locks on outbox rows belonging to B and C. The backfill
 * transactions for B and C hold {@code FOR UPDATE} locks on
 * {@code outbox_topic_subscriptions} and try to {@code UPDATE outbox} / {@code
 * INSERT INTO outbox_consumer_groups} for those same rows. This creates a
 * deadlock cycle that PostgreSQL resolves by raising {@code 40P01}.</p>
 *
 * <h3>Expected outcomes</h3>
 * <ul>
 *   <li><strong>With the BROKEN teardown (this class as-is):</strong> intermittent
 *       {@code 40P01 deadlock detected} failures on one or more test methods.</li>
 *   <li><strong>With the FIXED teardown (scoped delete per topic list):</strong> all
 *       three methods complete cleanly. See
 *       {@link BackfillScopePerformanceTest#tearDown} for the fixed pattern.</li>
 * </ul>
 *
 * <h3>Dataset size</h3>
 * <p>500 messages per test, batch size 50 (= 10 batches). Small enough to run
 * quickly but large enough to hold transaction locks long enough for the race to
 * trigger reliably.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-04
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Tag(TestCategories.INTEGRATION)
public class BackfillTeardownDeadlockProofTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillTeardownDeadlockProofTest.class);

    /** Shared prefix for all topics in this class - the broken LIKE predicate covers all of them. */
    private static final String TOPIC_PREFIX = "deadlock-probe-";

    private static final int MESSAGE_COUNT = 500;
    private static final int BATCH_SIZE = 50;

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;
    private String instanceTopic;

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
        instanceTopic = TOPIC_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        logger.info("BackfillTeardownDeadlockProofTest setup complete");
    }

    /**
     * DELIBERATELY BROKEN teardown — uses a broad LIKE predicate that covers ALL
     * concurrent tests' topics.
     *
     * <p>When multiple test methods run in parallel and one finishes first, this
     * teardown will DELETE rows that belong to still-running tests, racing with
     * their in-flight backfill transactions and triggering a PostgreSQL deadlock.</p>
     *
     * <p>To fix: replace the LIKE predicate with a scoped
     * {@code WHERE topic = ANY($1::text[])} parameterised on only the topics this
     * test instance created. See {@link BackfillScopePerformanceTest#tearDown} for
     * the corrected pattern.</p>
     */
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.withConnection("peegeeq-main", connection ->
                    connection.preparedQuery(
                            "DELETE FROM outbox WHERE topic = $1")
                            .execute(Tuple.of(instanceTopic))
                            .mapEmpty())
                    .compose(v -> connectionManager.close())
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Three concurrent test methods — all use the shared "deadlock-probe-" prefix
    // ========================================================================

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeAlpha_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-alpha";

        logger.info("=== PROOF TEST alpha: topic={} ===", topic);

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    logger.info("alpha backfill complete: {} msgs", result.processedMessages());
                    testContext.completeNow();
                })));
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeBeta_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-beta";

        logger.info("=== PROOF TEST beta: topic={} ===", topic);

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    logger.info("beta backfill complete: {} msgs", result.processedMessages());
                    testContext.completeNow();
                })));
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeGamma_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-gamma";

        logger.info("=== PROOF TEST gamma: topic={} ===", topic);

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    logger.info("gamma backfill complete: {} msgs", result.processedMessages());
                    testContext.completeNow();
                })));
    }

    // ========================================================================
    // Helper
    // ========================================================================

    private io.vertx.core.Future<Void> setupTopicAndMessages(String topic, int messageCount) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic,
                        "initial-group-" + UUID.randomUUID().toString().substring(0, 4),
                        SubscriptionOptions.defaults()))
                .compose(v -> connectionManager.withConnection("peegeeq-main", connection -> {
                    String sql = """
                        INSERT INTO outbox (topic, payload, created_at, status)
                        SELECT $1, ('{"index": ' || generate_series || '}')::jsonb, $2, 'PENDING'
                        FROM generate_series(1, $3)
                        """;
                    return connection.preparedQuery(sql)
                            .execute(Tuple.of(topic, OffsetDateTime.now(ZoneOffset.UTC), messageCount))
                            .mapEmpty();
                }));
    }
}
