package dev.mars.peegeeq.db.consumer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for CompletionTracker using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class CompletionTrackerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private CompletionTracker tracker;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx());
        
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-completion", connectionConfig, poolConfig);
        
        tracker = new CompletionTracker(connectionManager, "test-completion");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testCompletionTrackerCreation() {
        assertNotNull(tracker);
    }

    @Test
    void testMarkCompleted(VertxTestContext testContext) {
        String topic = "test-mark-completed-" + UUID.randomUUID().toString().substring(0, 8);
        // Clean any stale data for this topic from a prior withReuse container run
        cleanTestTopic(topic)
            .compose(v -> insertTestMessage(topic, 2))
            .compose(messageId -> insertSubscription(topic, "group1").map(v -> messageId))
            .compose(messageId -> tracker.markCompleted(messageId, "group1", topic).map(v -> messageId))
            .compose(messageId -> connectionManager.withConnection("test-completion", connection ->
                connection.query("SELECT COUNT(*) as count FROM outbox_consumer_groups WHERE message_id = " + messageId + " AND group_name = 'group1' AND status = 'COMPLETED'")
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getInteger("count"))
            ))
            .onComplete(testContext.succeeding(count -> testContext.verify(() -> {
                assertEquals(1, count);
                testContext.completeNow();
            })));
    }

    @Test
    void testMarkCompletedIdempotent(VertxTestContext testContext) {
        String topic = "test-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        // Clean any stale data for this topic from a prior withReuse container run
        cleanTestTopic(topic)
            .compose(v -> insertTestMessage(topic, 2))
            .compose(messageId -> insertSubscription(topic, "group1").map(v -> messageId))
            // Mark as completed twice
            .compose(messageId -> tracker.markCompleted(messageId, "group1", topic).map(v -> messageId))
            .compose(messageId -> tracker.markCompleted(messageId, "group1", topic).map(v -> messageId))
            // Should still have only one tracking row
            .compose(messageId -> connectionManager.withConnection("test-completion", connection ->
                connection.query("SELECT COUNT(*) as count FROM outbox_consumer_groups WHERE message_id = " + messageId + " AND group_name = 'group1'")
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getInteger("count"))
            ))
            .onComplete(testContext.succeeding(count -> testContext.verify(() -> {
                assertEquals(1, count);
                testContext.completeNow();
            })));
    }

    @Test
    void testMarkCompletedUpdatesCounter(VertxTestContext testContext) {
        String topic = "test-counter-" + UUID.randomUUID().toString().substring(0, 8);
        // Clean any stale data for this topic from a prior withReuse container run
        cleanTestTopic(topic)
            // Insert a test message with 2 required groups
            .compose(v -> insertTestMessage(topic, 2))
            .compose(messageId -> insertSubscription(topic, "group1").map(v -> messageId))
            // Mark as completed for first group
            .compose(messageId -> tracker.markCompleted(messageId, "group1", topic).map(v -> messageId))
            // Verify completed_consumer_groups was incremented
            .compose(messageId -> connectionManager.withConnection("test-completion", connection ->
                connection.query("SELECT completed_consumer_groups FROM outbox WHERE id = " + messageId)
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getInteger("completed_consumer_groups"))
            ))
            .onComplete(testContext.succeeding(completedGroups -> testContext.verify(() -> {
                assertEquals(1, completedGroups);
                testContext.completeNow();
            })));
    }

    @Test
    void testMarkCompletedAllGroupsCompletesMessage(VertxTestContext testContext) {
        String topic = "test-all-groups-" + UUID.randomUUID().toString().substring(0, 8);
        // Clean any stale data for this topic from a prior withReuse container run
        // Register as PUB_SUB so the insert trigger counts active subscriptions
        // Insert subscriptions BEFORE the message so the trigger counts them
        // Insert the message: trigger fires, sees PUB_SUB, counts 2 ACTIVE subs  required_consumer_groups=2
        cleanTestTopic(topic)
            .compose(v -> insertTestTopic(topic, "PUB_SUB"))
            .compose(v -> insertSubscription(topic, "group1"))
            .compose(v -> insertSubscription(topic, "group2"))
            .compose(v -> insertTestMessage(topic, 2))
            // Mark as completed for both groups
            .compose(messageId -> tracker.markCompleted(messageId, "group1", topic).map(v -> messageId))
            .compose(messageId -> tracker.markCompleted(messageId, "group2", topic).map(v -> messageId))
            // Verify message status is COMPLETED
            .compose(messageId -> connectionManager.withConnection("test-completion", connection ->
                connection.query("SELECT status FROM outbox WHERE id = " + messageId)
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getString("status"))
            ))
            .onComplete(testContext.succeeding(status -> testContext.verify(() -> {
                assertEquals("COMPLETED", status);
                testContext.completeNow();
            })));
    }

    private Future<Long> insertTestMessage(String topic, int requiredGroups) {
        return connectionManager.withConnection("test-completion", connection ->
            connection.query("INSERT INTO outbox (topic, payload, headers, status, required_consumer_groups, completed_consumer_groups) " +
                "VALUES ('" + topic + "', '{}'::jsonb, '{}'::jsonb, 'PENDING', " + requiredGroups + ", 0) RETURNING id")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getLong("id"))
        );
    }

    private Future<Void> insertTestTopic(String topic, String semantics) {
        return connectionManager.withConnection("test-completion", connection ->
            connection.query("INSERT INTO outbox_topics (topic, semantics) " +
                "VALUES ('" + topic + "', '" + semantics + "') ON CONFLICT (topic) DO UPDATE SET semantics = EXCLUDED.semantics")
                .execute()
        ).mapEmpty();
    }

    private Future<Void> insertSubscription(String topic, String groupName) {
        return connectionManager.withConnection("test-completion", connection ->
            connection.query("INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                "VALUES ('" + topic + "', '" + groupName + "', 'ACTIVE') " +
                "ON CONFLICT (topic, group_name) DO UPDATE SET subscription_status = 'ACTIVE', last_heartbeat_at = NOW()")
                .execute()
        ).mapEmpty();
    }

    private Future<Void> cleanTestTopic(String topic) {
        return connectionManager.withConnection("test-completion", connection ->
            connection.query("DELETE FROM outbox_consumer_groups WHERE message_id IN " +
                "(SELECT id FROM outbox WHERE topic = '" + topic + "')")
                .execute()
                .compose(ignored -> connection.query("DELETE FROM outbox WHERE topic = '" + topic + "'").execute())
                .compose(ignored -> connection.query("DELETE FROM outbox_topic_subscriptions WHERE topic = '" + topic + "'").execute())
                .compose(ignored -> connection.query("DELETE FROM outbox_topics WHERE topic = '" + topic + "'").execute())
        ).mapEmpty();
    }
}

