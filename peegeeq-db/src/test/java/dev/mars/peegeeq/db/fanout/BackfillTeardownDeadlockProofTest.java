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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
 * {@code "deadlock-probe-"}. The {@code @AfterEach tearDown()} scopes deletion
 * to the exact topic owned by that test instance:</p>
 * <pre>{@code
 *   DELETE FROM outbox WHERE topic = $1
 * }</pre>
 * <p>This is the regression proof for the former broad-prefix teardown race:
 * one test's cleanup must never lock or delete another concurrent test's rows.</p>
 *
 * <h3>Expected outcomes</h3>
 * <ul>
 *   <li>All three methods complete cleanly with no {@code 40P01} deadlock.</li>
 *   <li>Each teardown removes only the rows for its exact topic.</li>
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

    /** Shared prefix retained to exercise concurrent topics with similar names. */
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
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(20)
                .shared(false)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");
        instanceTopic = TOPIC_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    }

    /**
     * Deletes only the exact topic owned by this test instance, then closes the
     * isolated connection manager even if cleanup fails.
     */
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.withConnection("peegeeq-main", connection ->
                    connection.preparedQuery(
                            "DELETE FROM outbox WHERE topic = $1")
                            .execute(Tuple.of(instanceTopic))
                            .mapEmpty())
                    .eventually(() -> connectionManager.close())
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Three concurrent test methods  all use the shared "deadlock-probe-" prefix
    // ========================================================================

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeAlpha_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-alpha";

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeBeta_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-beta";

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void testProbeGamma_BackfillWhileTeardownRaces(VertxTestContext testContext) {
        String topic = instanceTopic;
        String groupName = "probe-grp-gamma";

        setupTopicAndMessages(topic, MESSAGE_COUNT)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> backfillService.startBackfill(topic, groupName, BATCH_SIZE, 0, BackfillScope.PENDING_ONLY))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertTrue(result.processedMessages() >= MESSAGE_COUNT - BATCH_SIZE,
                            "expected >= " + (MESSAGE_COUNT - BATCH_SIZE) + " but was " + result.processedMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
                .compose(v -> connectionManager.withTransaction("peegeeq-main", connection -> {
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
