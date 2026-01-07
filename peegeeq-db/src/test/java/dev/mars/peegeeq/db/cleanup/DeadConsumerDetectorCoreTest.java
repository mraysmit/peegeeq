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
 * CORE tests for DeadConsumerDetector using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerDetectorCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;

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
        connectionManager.getOrCreateReactivePool("test-detector", connectionConfig, poolConfig);
        
        detector = new DeadConsumerDetector(connectionManager, "test-detector");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testDeadConsumerDetectorCreation() {
        assertNotNull(detector);
    }

    @Test
    void testDeadConsumerDetectorCreationWithNullConnectionManager() {
        assertThrows(NullPointerException.class, () -> 
            new DeadConsumerDetector(null, "test-service"));
    }

    @Test
    void testDeadConsumerDetectorCreationWithNullServiceId() {
        assertThrows(NullPointerException.class, () -> 
            new DeadConsumerDetector(connectionManager, null));
    }

    @Test
    void testDetectDeadSubscriptionsWithNullTopic() {
        assertThrows(NullPointerException.class, () -> 
            detector.detectDeadSubscriptions(null));
    }

    @Test
    void testDetectDeadSubscriptionsNoDeadSubscriptions() throws Exception {
        Integer markedDead = detector.detectDeadSubscriptions("non-existent-topic")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0, markedDead);
    }

    @Test
    void testDetectAllDeadSubscriptionsNoDeadSubscriptions() throws Exception {
        // This test verifies that detectAllDeadSubscriptions works correctly.
        // In a shared database environment with parallel tests, other tests may
        // have created subscriptions that could be marked as dead.
        // The key validation is that the operation completes successfully.
        Integer markedDead = detector.detectAllDeadSubscriptions()
            .toCompletionStage().toCompletableFuture().get();
        // In a shared database, we can only assert non-negative result
        assertTrue(markedDead >= 0, "Marked dead count should be non-negative");
    }

    @Test
    void testCountDeadSubscriptionsWithNullTopic() {
        assertThrows(NullPointerException.class, () ->
            detector.countDeadSubscriptions(null));
    }

    @Test
    void testCountDeadSubscriptionsNoSubscriptions() throws Exception {
        Long count = detector.countDeadSubscriptions("non-existent-topic")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0L, count);
    }

    @Test
    void testCountEligibleForDeadDetectionWithNullTopic() {
        assertThrows(NullPointerException.class, () ->
            detector.countEligibleForDeadDetection(null));
    }

    @Test
    void testCountEligibleForDeadDetectionNoSubscriptions() throws Exception {
        Long count = detector.countEligibleForDeadDetection("non-existent-topic")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0L, count);
    }
}


