package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.vertx.core.Future;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.INTEGRATION)
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
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertTrue(healthy, "Database should be healthy");
                testContext.completeNow();
            })));
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

    /**
     * close() must leave the manager's pools open until the manager's close hooks have run. The
     * hook stands for a queue factory whose consumer still writes to the database while it
     * closes. The service used to close the manager's client factory before it started the
     * manager close, so that write ran on a closed pool.
     */
    @Test
    void closeRunsTheManagerCloseHooksBeforeThePoolsClose(VertxTestContext testContext) throws Exception {
        databaseService = new PgDatabaseService(manager);
        Promise<Integer> hookQuery = Promise.promise();
        databaseService.registerCloseHook(new PeeGeeQCloseHook() {
            @Override
            public String name() {
                return "query-during-close";
            }

            @Override
            public Future<Void> closeReactive() {
                return databaseService.getPool().query("SELECT 1").execute()
                    .map(rows -> rows.iterator().next().getInteger(0))
                    .andThen(hookQuery)
                    .mapEmpty();
            }
        });

        databaseService.close();

        hookQuery.future()
            .onSuccess(value -> testContext.verify(() -> {
                assertEquals(1, value, "the close hook's query should run on an open pool");
                testContext.completeNow();
            }))
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
