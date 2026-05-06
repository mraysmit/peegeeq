package dev.mars.peegeeq.db.recovery;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for StuckMessageRecoveryManager using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class StuckMessageRecoveryManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool pool;
    private StuckMessageRecoveryManager recoveryManager;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
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
        pool = connectionManager.getOrCreateReactivePool("test-recovery", connectionConfig, poolConfig);

        // Remove any PROCESSING rows left by previous tests so count assertions start clean
        pool.withConnection(conn ->
            conn.preparedQuery("DELETE FROM outbox WHERE status = 'PROCESSING'").execute()
        ).onSuccess(rows -> {
            recoveryManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), true);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testStuckMessageRecoveryManagerCreation() {
        assertNotNull(recoveryManager);
    }

    @Test
    void testStuckMessageRecoveryManagerCreationEnabled() {
        StuckMessageRecoveryManager manager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), true);
        assertNotNull(manager);
    }

    @Test
    void testStuckMessageRecoveryManagerCreationDisabled() {
        StuckMessageRecoveryManager manager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        assertNotNull(manager);
    }

    @Test
    void testRecoverStuckMessagesWhenDisabled(VertxTestContext testContext) {
        StuckMessageRecoveryManager disabledManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        disabledManager.recoverStuckMessages()
            .onComplete(testContext.succeeding(recovered -> testContext.verify(() -> {
                assertEquals(0, (int) recovered);
                testContext.completeNow();
            })));
    }

    @Test
    void testRecoverStuckMessagesNoStuckMessages(VertxTestContext testContext) {
        recoveryManager.recoverStuckMessages()
            .onComplete(testContext.succeeding(recovered -> testContext.verify(() -> {
                assertEquals(0, (int) recovered);
                testContext.completeNow();
            })));
    }

    @Test
    void testGetRecoveryStats(VertxTestContext testContext) {
        recoveryManager.getRecoveryStats()
            .onComplete(testContext.succeeding(stats -> testContext.verify(() -> {
                assertNotNull(stats);
                assertTrue(stats.isEnabled());
                assertEquals(0, stats.getStuckMessagesCount());
                assertEquals(0, stats.getTotalProcessingCount());
                testContext.completeNow();
            })));
    }

    @Test
    void testGetRecoveryStatsWhenDisabled(VertxTestContext testContext) {
        StuckMessageRecoveryManager disabledManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        disabledManager.getRecoveryStats()
            .onComplete(testContext.succeeding(stats -> testContext.verify(() -> {
                assertNotNull(stats);
                assertFalse(stats.isEnabled());
                assertEquals(0, stats.getStuckMessagesCount());
                assertEquals(0, stats.getTotalProcessingCount());
                testContext.completeNow();
            })));
    }

    @Test
    void testRecoveryStatsToString() {
        StuckMessageRecoveryManager.RecoveryStats stats = new StuckMessageRecoveryManager.RecoveryStats(5, 10, true);
        String toString = stats.toString();
        assertTrue(toString.contains("stuck=5"));
        assertTrue(toString.contains("totalProcessing=10"));
        assertTrue(toString.contains("enabled=true"));
    }

    @Test
    void testRecoveryStatsGetters() {
        StuckMessageRecoveryManager.RecoveryStats stats = new StuckMessageRecoveryManager.RecoveryStats(5, 10, true);
        assertEquals(5, stats.getStuckMessagesCount());
        assertEquals(10, stats.getTotalProcessingCount());
        assertTrue(stats.isEnabled());
    }

    @Test
    void testRecoverStuckMessagesMultipleCalls(VertxTestContext testContext) {
        recoveryManager.recoverStuckMessages()
            .compose(count1 -> {
                assertTrue(count1 >= 0);
                return recoveryManager.recoverStuckMessages();
            })
            .onComplete(testContext.succeeding(count2 -> testContext.verify(() -> {
                assertTrue(count2 >= 0);
                testContext.completeNow();
            })));
    }

    @Test
    void testGetRecoveryStatsMultipleCalls(VertxTestContext testContext) {
        recoveryManager.getRecoveryStats()
            .compose(stats1 -> {
                assertNotNull(stats1);
                return recoveryManager.getRecoveryStats();
            })
            .onComplete(testContext.succeeding(stats2 -> testContext.verify(() -> {
                assertNotNull(stats2);
                testContext.completeNow();
            })));
    }

    @Test
    void testRecoveryManagerWithDifferentTimeouts(VertxTestContext testContext) {
        StuckMessageRecoveryManager manager1 = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(1), true);
        StuckMessageRecoveryManager manager2 = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(10), true);
        manager1.recoverStuckMessages()
            .compose(count1 -> {
                assertTrue(count1 >= 0);
                return manager2.recoverStuckMessages();
            })
            .onComplete(testContext.succeeding(count2 -> testContext.verify(() -> {
                assertTrue(count2 >= 0);
                testContext.completeNow();
            })));
    }
}


