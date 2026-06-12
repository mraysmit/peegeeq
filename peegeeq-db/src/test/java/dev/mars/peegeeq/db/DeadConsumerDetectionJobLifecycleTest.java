package dev.mars.peegeeq.db;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Task M7: Service Manager Detection Job Lifecycle.
 *
 * <p>Verifies that {@link PeeGeeQManager#start()} starts the
 * {@link DeadConsumerDetectionJob} and that shutdown stops it.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-07
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "dead-consumer-detection", mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerDetectionJobLifecycleTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionJobLifecycleTest.class);

    private PeeGeeQManager manager;
    private Properties testProps;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);testProps.setProperty("peegeeq.database.pool.min-size", "2");
        testProps.setProperty("peegeeq.database.pool.max-size", "3");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        testProps.setProperty("peegeeq.health.check-interval", "PT5S");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        testProps.setProperty("peegeeq.migration.enabled", "false");
        testProps.setProperty("peegeeq.migration.auto-migrate", "false");

        // Enable dead consumer detection with minimum allowed interval for testing
        testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "true");
        testProps.setProperty("peegeeq.queue.dead-consumer-detection.interval", "PT10S");
        // Disable retry job  this test does not need it and it mutates outbox_consumer_groups globally
        testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");

        PeeGeeQConfiguration configuration = new PeeGeeQConfiguration("test", testProps);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        logger.info("DeadConsumerDetectionJobLifecycleTest setup complete");
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try {
                manager.closeReactive()
                    .onFailure(t -> logger.warn("Error during manager teardown: {}", t.getMessage()));
            } catch (Exception e) {
                logger.warn("Exception during tearDown: {}", e.getMessage());
            }
        }
    }

    @Test
    void testDetectionJobStartsWithManager(VertxTestContext testContext) {
        logger.info("=== Testing detection job starts with manager ===");

        manager.start()
            // Wait for the initial detection run to complete (runs immediately on start)
            .compose(v -> manager.getVertx().timer(3000))
            .compose(v -> {
                DeadConsumerDetectionJob job = manager.getDeadConsumerDetectionJob();
                assertNotNull(job, "Detection job should be created after start");
                assertTrue(job.isRunning(), "Detection job should be running");
                assertTrue(job.getTotalRunCount() > 0,
                        "Detection job should have run at least once, actual: " + job.getTotalRunCount());

                logger.info("Detection job running: runCount={}", job.getTotalRunCount());
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testDetectionJobStopsWithManager(VertxTestContext testContext) {
        logger.info("=== Testing detection job stops with manager ===");

        manager.start()
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> {
                DeadConsumerDetectionJob job = manager.getDeadConsumerDetectionJob();
                assertNotNull(job, "Detection job should be running before stop");
                assertTrue(job.isRunning(), "Detection job should be running before stop");
                return manager.stop();
            })
            .compose(v -> {
                // After stop(), the job reference is nulled and job.stop() called
                DeadConsumerDetectionJob job = manager.getDeadConsumerDetectionJob();
                assertNull(job, "Detection job reference should be null after stop");
                assertFalse(manager.isStarted(), "Manager should not be started after stop");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testDetectionJobDisabledByConfig(VertxTestContext testContext) {
        logger.info("=== Testing detection job disabled by config ===");

        // Override to disable detection
        Properties disabledProps = new Properties();
        testProps.forEach((k, v) -> disabledProps.setProperty(k.toString(), v.toString()));
        disabledProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        PeeGeeQConfiguration disabledConfig = new PeeGeeQConfiguration("test", disabledProps);

        // Close the manager from setUp and create a new one with detection disabled
        manager.closeReactive()
            .transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Error closing manager: {}", ar.cause().getMessage());
                }
                return Future.<Void>succeededFuture();
            })
            .compose(v -> {
                manager = new PeeGeeQManager(disabledConfig, new SimpleMeterRegistry());
                return manager.start();
            })
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> {
                DeadConsumerDetectionJob job = manager.getDeadConsumerDetectionJob();
                assertNull(job, "Detection job should NOT be created when disabled");
                assertTrue(manager.isStarted(), "Manager should still start successfully");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
}
