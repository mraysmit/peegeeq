package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.vertx.core.Future;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
public class PgDatabaseServiceCoreTest extends BaseIntegrationTest {

    private PgDatabaseService databaseService;

    @Test
    void testPgDatabaseServiceCreation(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        assertNotNull(databaseService);
        testContext.completeNow();
    }

    @Test
    void testInitialize(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testStart(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.start())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testStop(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.start())
            .compose(v -> databaseService.stop())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetConnectionProvider(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        dev.mars.peegeeq.api.database.ConnectionProvider provider = databaseService.getConnectionProvider();
        assertNotNull(provider);
        testContext.completeNow();
    }

    @Test
    void testGetMetricsProvider(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        dev.mars.peegeeq.api.database.MetricsProvider provider = databaseService.getMetricsProvider();
        assertNotNull(provider);
        testContext.completeNow();
    }

    @Test
    void testIsHealthy(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        boolean healthy = databaseService.isHealthy();
        testContext.completeNow();
    }

    @Test
    void testIsRunning(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        boolean running = databaseService.isRunning();
        testContext.completeNow();
    }

    @Test
    void testPerformHealthCheck(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.start())
            .compose(v -> databaseService.performHealthCheck())
            .onSuccess(healthy -> testContext.verify(() -> {
                assertTrue(healthy, "Database should be healthy");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testRunMigrations(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.runMigrations())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testClose(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.start())
            .compose(v -> {
                try {
                    databaseService.close();
                    return Future.<Void>succeededFuture();
                } catch (Exception e) {
                    return Future.<Void>failedFuture(e);
                }
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMultipleInitializeCalls(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.initialize())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMultipleStartCalls(VertxTestContext testContext) {
        databaseService = new PgDatabaseService(manager);
        
        databaseService.initialize()
            .compose(v -> databaseService.start())
            .compose(v -> databaseService.start())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
}
