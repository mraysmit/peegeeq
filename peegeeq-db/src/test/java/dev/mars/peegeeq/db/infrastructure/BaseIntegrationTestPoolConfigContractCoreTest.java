package dev.mars.peegeeq.db.infrastructure;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2b Primary test for the property-key mismatch between
 * {@code BaseIntegrationTest.setupTestConfiguration()} and
 * {@link PeeGeeQConfiguration#getPoolConfig()}.
 *
 * <p>This CORE test replicates the exact system properties that
 * {@code setupTestConfiguration()} sets for pool configuration,
 * then asserts that the resulting {@link PgPoolConfig} reflects
 * the intended test values (short timeouts, small pool, non-shared).</p>
 *
 * <p>Tagged CORE no database, no TestContainers. Runs in every
 * {@code mvn test} invocation. Cannot be blocked by connection exhaustion.</p>
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
class BaseIntegrationTestPoolConfigContractCoreTest {

    private Properties testProps;

    @BeforeEach
    void setUp() {
        testProps = new Properties();
        testProps.setProperty("peegeeq.database.pool.min-size", "1");
        testProps.setProperty("peegeeq.database.pool.max-size", "3");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
    }

    @AfterEach
    void tearDown() {
        // No System properties to clean up
    }

    @Test
    void poolConfigUsesShortIdleTimeoutForFastTeardown() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID(), testProps);
        Duration idleTimeout = cfg.getPoolConfig().getIdleTimeout();
        assertEquals(Duration.ofMillis(2000), idleTimeout,
            "idle timeout must match the 2000 ms set by setupTestConfiguration()");
    }

    @Test
    void poolConfigUsesShortConnectionTimeoutForFastFailure() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID(), testProps);
        Duration connTimeout = cfg.getPoolConfig().getConnectionTimeout();
        assertEquals(Duration.ofMillis(5000), connTimeout,
            "connection timeout must match the 5000 ms set by setupTestConfiguration()");
    }

    @Test
    void poolConfigUsesNonSharedPoolsForDeterministicCleanup() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID(), testProps);
        assertFalse(cfg.getPoolConfig().isShared(),
            "shared pools defer connection close via reference counting; tests need deterministic close");
    }

    @Test
    void poolConfigUsesSmallMaxSizeForParallelExecutionHeadroom() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID(), testProps);
        int maxSize = cfg.getPoolConfig().getMaxSize();
        assertTrue(maxSize <= 3,
            "test pool max-size must be <= 3 for parallel execution headroom; actual: " + maxSize);
    }
}
