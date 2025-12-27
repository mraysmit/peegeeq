package dev.mars.peegeeq.db.consumer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

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
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx());
        
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-completion", connectionConfig, poolConfig);
        
        tracker = new CompletionTracker(connectionManager, "test-completion");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testCompletionTrackerCreation() {
        assertNotNull(tracker);
    }

    @Test
    void testMarkCompleted() throws Exception {
        // First, insert a test message into the outbox table
        Long messageId = insertTestMessage("test-topic", 2);

        // Mark as completed for first group
        tracker.markCompleted(messageId, "group1", "test-topic")
            .toCompletionStage().toCompletableFuture().get();

        // Verify the tracking row was created
        Integer count = connectionManager.withConnection("test-completion", connection ->
            connection.query("SELECT COUNT(*) as count FROM outbox_consumer_groups WHERE message_id = " + messageId + " AND group_name = 'group1' AND status = 'COMPLETED'")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("count"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(1, count);
    }

    @Test
    void testMarkCompletedIdempotent() throws Exception {
        // Insert a test message
        Long messageId = insertTestMessage("test-topic", 2);

        // Mark as completed twice
        tracker.markCompleted(messageId, "group1", "test-topic")
            .toCompletionStage().toCompletableFuture().get();
        tracker.markCompleted(messageId, "group1", "test-topic")
            .toCompletionStage().toCompletableFuture().get();

        // Should still have only one tracking row
        Integer count = connectionManager.withConnection("test-completion", connection ->
            connection.query("SELECT COUNT(*) as count FROM outbox_consumer_groups WHERE message_id = " + messageId + " AND group_name = 'group1'")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("count"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(1, count);
    }

    @Test
    void testMarkCompletedUpdatesCounter() throws Exception {
        // Insert a test message with 2 required groups
        Long messageId = insertTestMessage("test-topic", 2);

        // Mark as completed for first group
        tracker.markCompleted(messageId, "group1", "test-topic")
            .toCompletionStage().toCompletableFuture().get();

        // Verify completed_consumer_groups was incremented
        Integer completedGroups = connectionManager.withConnection("test-completion", connection ->
            connection.query("SELECT completed_consumer_groups FROM outbox WHERE id = " + messageId)
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("completed_consumer_groups"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(1, completedGroups);
    }

    @Test
    void testMarkCompletedAllGroupsCompletesMessage() throws Exception {
        // Insert a test message with 2 required groups
        Long messageId = insertTestMessage("test-topic", 2);

        // Mark as completed for both groups
        tracker.markCompleted(messageId, "group1", "test-topic")
            .toCompletionStage().toCompletableFuture().get();
        tracker.markCompleted(messageId, "group2", "test-topic")
            .toCompletionStage().toCompletableFuture().get();

        // Verify message status is COMPLETED
        String status = connectionManager.withConnection("test-completion", connection ->
            connection.query("SELECT status FROM outbox WHERE id = " + messageId)
                .execute()
                .map(rowSet -> rowSet.iterator().next().getString("status"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals("COMPLETED", status);
    }

    private Long insertTestMessage(String topic, int requiredGroups) throws Exception {
        return connectionManager.withConnection("test-completion", connection ->
            connection.query("INSERT INTO outbox (topic, payload, headers, status, required_consumer_groups, completed_consumer_groups) " +
                "VALUES ('" + topic + "', '{}'::jsonb, '{}'::jsonb, 'PENDING', " + requiredGroups + ", 0) RETURNING id")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getLong("id"))
        ).toCompletionStage().toCompletableFuture().get();
    }
}

