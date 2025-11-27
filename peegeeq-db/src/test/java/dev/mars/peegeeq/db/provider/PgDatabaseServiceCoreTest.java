package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgDatabaseService using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class PgDatabaseServiceCoreTest extends BaseIntegrationTest {

    private PgDatabaseService databaseService;

    @BeforeEach
    void setUp() throws Exception {
        databaseService = new PgDatabaseService(manager);
    }

    @Test
    void testPgDatabaseServiceCreation() {
        assertNotNull(databaseService);
    }

    @Test
    void testInitialize() throws Exception {
        CompletableFuture<Void> future = databaseService.initialize();
        assertNotNull(future);
        future.get(); // Should complete successfully
    }

    @Test
    void testStart() throws Exception {
        CompletableFuture<Void> future = databaseService.start();
        assertNotNull(future);
        future.get(); // Should complete successfully
    }

    @Test
    void testStartReactive() throws Exception {
        io.vertx.core.Future<Void> future = databaseService.startReactive();
        assertNotNull(future);
        future.toCompletionStage().toCompletableFuture().get(); // Should complete successfully
    }

    @Test
    void testStop() throws Exception {
        CompletableFuture<Void> future = databaseService.stop();
        assertNotNull(future);
        future.get(); // Should complete successfully
    }

    @Test
    void testGetConnectionProvider() {
        dev.mars.peegeeq.api.database.ConnectionProvider provider = databaseService.getConnectionProvider();
        assertNotNull(provider);
    }

    @Test
    void testGetMetricsProvider() {
        dev.mars.peegeeq.api.database.MetricsProvider provider = databaseService.getMetricsProvider();
        assertNotNull(provider);
    }

    @Test
    void testIsHealthy() {
        boolean healthy = databaseService.isHealthy();
        // Just verify no exception thrown
    }

    @Test
    void testIsRunning() {
        boolean running = databaseService.isRunning();
        // Just verify no exception thrown
    }

    @Test
    void testPerformHealthCheck() throws Exception {
        CompletableFuture<Boolean> future = databaseService.performHealthCheck();
        assertNotNull(future);
        Boolean healthy = future.get();
        assertNotNull(healthy);
    }

    @Test
    void testRunMigrations() throws Exception {
        CompletableFuture<Void> future = databaseService.runMigrations();
        assertNotNull(future);
        future.get(); // Should complete successfully
    }

    @Test
    void testClose() throws Exception {
        databaseService.close();
        // Verify no exception thrown
    }

    @Test
    void testMultipleInitializeCalls() throws Exception {
        databaseService.initialize().get();
        databaseService.initialize().get();
        // Should be idempotent
    }

    @Test
    void testMultipleStartCalls() throws Exception {
        databaseService.start().get();
        databaseService.start().get();
        // Should be idempotent
    }
}


