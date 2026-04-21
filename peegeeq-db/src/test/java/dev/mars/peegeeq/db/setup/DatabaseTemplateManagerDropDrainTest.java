package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dropDatabase} must poll {@code pg_stat_activity} until all
 * connections are confirmed gone before issuing {@code DROP DATABASE}.
 *
 * <p><strong>The bug:</strong> {@code pg_terminate_backend()} signals backends to terminate
 * but returns before they have fully disconnected. The current 100 ms timer assumes that OS
 * cleanup completes within that window — a false assumption under load or when backends are
 * executing long-running queries when the signal arrives.
 *
 * <p><strong>The fix:</strong> replace the fixed timer with a poll loop that:
 * <ol>
 *   <li>calls {@code pg_terminate_backend} for any remaining sessions,</li>
 *   <li>checks {@code pg_stat_activity} for the connection count,</li>
 *   <li>issues {@code DROP DATABASE} only when the count is confirmed zero, and</li>
 *   <li>fails with a timeout error if the count never reaches zero after 20 attempts.</li>
 * </ol>
 *
 * <p><strong>Red-test note:</strong> The current implementation uses a 100 ms fixed timer.
 * On a loaded CI host or when 3 backends are executing {@code pg_sleep(1)} at the moment
 * {@code pg_terminate_backend} fires, the kernel cleanup window can exceed 100 ms and the
 * subsequent {@code DROP DATABASE} fails with "there are N other sessions using the database".
 * In a fast local Testcontainers run the race may not manifest, but the implementation does
 * not guarantee correctness. The poll-based fix is deterministically correct.
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
class DatabaseTemplateManagerDropDrainTest extends BaseIntegrationTest {

    private DatabaseTemplateManager databaseTemplateManager;
    @SuppressWarnings("rawtypes")
    private PostgreSQLContainer postgres;

    @BeforeEach
    void setUp() {
        postgres = getPostgres();
        databaseTemplateManager = new DatabaseTemplateManager(manager.getVertx());
    }

    /**
     * RED — the 100 ms fixed timer does not guarantee that all backends have left
     * {@code pg_stat_activity} before {@code DROP DATABASE} is issued.
     *
     * <p>Three connections running {@code pg_sleep(1)} are established to the target database
     * before {@code dropDatabase} is called. The backends are signalled by
     * {@code pg_terminate_backend}, but their OS-level process cleanup is asynchronous.
     * On loaded hosts, those backends are still visible in {@code pg_stat_activity} when the
     * 100 ms timer fires, causing {@code DROP DATABASE} to fail.
     *
     * <p>After the poll-based fix is applied, the same test passes reliably because the
     * implementation waits for a confirmed zero connection count before dropping.
     */
    @Test
    void dropDatabase_mustSucceedWhenBackendsAreSlowToDisconnect(VertxTestContext testContext)
            throws InterruptedException {

        String testDbName = "test_drain_" + System.currentTimeMillis();
        Vertx vertx = manager.getVertx();

        // Step 1: create the target database.
        databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(),
                testDbName, "template0", "UTF8", Map.of())
            .compose(ignored -> {

                // Step 2: open a pool to the newly created database and fire 3 long-running
                // queries. pg_sleep(1) keeps 3 backend processes alive for 1 second — well
                // beyond the 100 ms timer. pg_terminate_backend will signal these backends,
                // but their cleanup is asynchronous at the OS level.
                Pool targetPool = buildTargetPool(vertx, testDbName);
                for (int i = 0; i < 3; i++) {
                    // fire-and-forget: each connection holds a backend alive running pg_sleep
                    targetPool.query("SELECT pg_sleep(1)").execute();
                }

                // Step 3: call dropDatabase immediately — while the 3 pg_sleep backends are
                // still running. The current 100 ms timer races against their cleanup.
                // The poll-based fix terminates and re-checks until the count is confirmed zero.
                Pool adminPool = buildAdminPool(vertx);
                return adminPool.withConnection(conn ->
                        databaseTemplateManager.dropDatabase(conn, testDbName))
                    .eventually(adminPool::close)
                    .eventually(() ->
                        // close targetPool; may fail because the database was dropped — ignore
                        targetPool.close().recover(err -> Future.succeededFuture()));
            })
            .compose(ignored -> {

                // Step 4: verify the database is gone.
                Pool adminPool = buildAdminPool(vertx);
                return adminPool.withConnection(conn ->
                        databaseTemplateManager.databaseExists(conn, testDbName))
                    .eventually(adminPool::close);
            })
            .onSuccess(exists -> {
                testContext.verify(() ->
                    assertFalse(exists, "Database should not exist after dropDatabase succeeds"));
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(
            testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "Test did not complete within 30 seconds");
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }

    // ------ private helpers -------------------------------------------------------

    private Pool buildTargetPool(Vertx vertx, String database) {
        return PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(3))
            .connectingTo(new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(database)
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()))
            .using(vertx)
            .build();
    }

    private Pool buildAdminPool(Vertx vertx) {
        return PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1))
            .connectingTo(new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase("postgres")
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()))
            .using(vertx)
            .build();
    }
}
