package dev.mars.peegeeq.db.recovery;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

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
        pool = connectionManager.getOrCreateReactivePool("test-recovery", connectionConfig, poolConfig);
        
        recoveryManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
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
    void testRecoverStuckMessagesWhenDisabled() {
        StuckMessageRecoveryManager disabledManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        int recovered = disabledManager.recoverStuckMessages();
        assertEquals(0, recovered);
    }

    @Test
    void testRecoverStuckMessagesNoStuckMessages() {
        int recovered = recoveryManager.recoverStuckMessages();
        assertEquals(0, recovered);
    }

    @Test
    void testGetRecoveryStats() {
        StuckMessageRecoveryManager.RecoveryStats stats = recoveryManager.getRecoveryStats();
        assertNotNull(stats);
        assertTrue(stats.isEnabled());
        assertEquals(0, stats.getStuckMessagesCount());
        assertEquals(0, stats.getTotalProcessingCount());
    }

    @Test
    void testGetRecoveryStatsWhenDisabled() {
        StuckMessageRecoveryManager disabledManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        StuckMessageRecoveryManager.RecoveryStats stats = disabledManager.getRecoveryStats();
        assertNotNull(stats);
        assertFalse(stats.isEnabled());
        assertEquals(0, stats.getStuckMessagesCount());
        assertEquals(0, stats.getTotalProcessingCount());
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
    void testRecoverStuckMessagesMultipleCalls() {
        // First call
        int count1 = recoveryManager.recoverStuckMessages();
        assertTrue(count1 >= 0);

        // Second call
        int count2 = recoveryManager.recoverStuckMessages();
        assertTrue(count2 >= 0);
    }

    @Test
    void testGetRecoveryStatsMultipleCalls() {
        // First call
        StuckMessageRecoveryManager.RecoveryStats stats1 = recoveryManager.getRecoveryStats();
        assertNotNull(stats1);

        // Second call
        StuckMessageRecoveryManager.RecoveryStats stats2 = recoveryManager.getRecoveryStats();
        assertNotNull(stats2);
    }

    @Test
    void testRecoveryManagerWithDifferentTimeouts() {
        StuckMessageRecoveryManager manager1 = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(1), true);
        int count1 = manager1.recoverStuckMessages();
        assertTrue(count1 >= 0);

        StuckMessageRecoveryManager manager2 = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(10), true);
        int count2 = manager2.recoverStuckMessages();
        assertTrue(count2 >= 0);
    }
}


