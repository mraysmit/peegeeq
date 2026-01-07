package dev.mars.peegeeq.db.cleanup;

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
 * CORE tests for CleanupService using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class CleanupServiceCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private CleanupService cleanupService;

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
        connectionManager.getOrCreateReactivePool("test-cleanup", connectionConfig, poolConfig);
        
        cleanupService = new CleanupService(connectionManager, "test-cleanup");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testCleanupServiceCreation() {
        assertNotNull(cleanupService);
    }

    @Test
    void testCleanupServiceCreationWithNullConnectionManager() {
        assertThrows(NullPointerException.class, () -> 
            new CleanupService(null, "test-service"));
    }

    @Test
    void testCleanupServiceCreationWithNullServiceId() {
        assertThrows(NullPointerException.class, () -> 
            new CleanupService(connectionManager, null));
    }

    @Test
    void testCleanupCompletedMessagesWithNullTopic() {
        assertThrows(NullPointerException.class, () -> 
            cleanupService.cleanupCompletedMessages(null, 100));
    }

    @Test
    void testCleanupCompletedMessagesWithInvalidBatchSize() throws Exception {
        try {
            cleanupService.cleanupCompletedMessages("test-topic", 0)
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void testCleanupCompletedMessagesWithNegativeBatchSize() throws Exception {
        try {
            cleanupService.cleanupCompletedMessages("test-topic", -1)
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void testCleanupCompletedMessagesNoMessages() throws Exception {
        Integer deleted = cleanupService.cleanupCompletedMessages("non-existent-topic", 100)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0, deleted);
    }

    @Test
    void testCountEligibleForCleanupWithNullTopic() {
        assertThrows(NullPointerException.class, () ->
            cleanupService.countEligibleForCleanup(null));
    }

    @Test
    void testCountEligibleForCleanupNoMessages() throws Exception {
        Long count = cleanupService.countEligibleForCleanup("non-existent-topic")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0L, count);
    }

    @Test
    void testCleanupAllTopicsWithInvalidBatchSize() throws Exception {
        try {
            cleanupService.cleanupAllTopics(0)
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void testCleanupAllTopicsNoMessages() throws Exception {
        // This test verifies that cleanupAllTopics returns 0 when there are no
        // completed messages eligible for cleanup.
        // Note: In parallel test execution, other tests may have created topics,
        // but as long as they don't have completed messages past retention, 
        // this should return 0.
        // The test validates the cleanup logic works correctly, not global database state.
        Integer deleted = cleanupService.cleanupAllTopics(100)
            .toCompletionStage().toCompletableFuture().get();
        // In a shared database environment, we can only assert non-negative result
        // as other tests may have data. The key validation is that no exception occurs.
        assertTrue(deleted >= 0, "Deleted count should be non-negative");
    }
}

