package dev.mars.peegeeq.db;

import dev.mars.peegeeq.db.consumer.ConsumerGroupRetryJob;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ConsumerGroupRetryJob lifecycle within PeeGeeQManager.
 *
 * <p>Verifies that {@link PeeGeeQManager#start()} starts the
 * {@link ConsumerGroupRetryJob} and that shutdown stops it.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-11
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "system-properties")
@ResourceLock(value = "consumer-group-retry-job", mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
public class ConsumerGroupRetryJobLifecycleTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupRetryJobLifecycleTest.class);

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.database.schema", "public");
        testProps.setProperty("peegeeq.database.pool.min-size", "2");
        testProps.setProperty("peegeeq.database.pool.max-size", "10");
        testProps.setProperty("peegeeq.health.check-interval", "PT5S");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        testProps.setProperty("peegeeq.migration.enabled", "false");
        testProps.setProperty("peegeeq.migration.auto-migrate", "false");

        // Enable consumer group retry with minimum allowed interval for testing
        testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "true");
        testProps.setProperty("peegeeq.queue.consumer-group-retry.interval", "PT10S");

        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration configuration = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        logger.info("ConsumerGroupRetryJobLifecycleTest setup complete");
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try {
                manager.closeReactive()
                    .recover(t -> {
                        System.err.println("Error during manager teardown: " + t.getMessage());
                        return Future.succeededFuture();
                    });
            } catch (Exception e) {
                System.err.println("Exception during tearDown: " + e.getMessage());
            }
        }
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @Test
    void testRetryJobStartsWithManager(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing retry job starts with manager ===");

        manager.start()
            .compose(v -> manager.getVertx().timer(3000))
            .compose(v -> {
                ConsumerGroupRetryJob job = manager.getConsumerGroupRetryJob();
                assertNotNull(job, "Retry job should be created after start");
                assertTrue(job.isRunning(), "Retry job should be running");
                assertTrue(job.getTotalRunCount() > 0,
                        "Retry job should have run at least once, actual: " + job.getTotalRunCount());

                logger.info("Retry job running: runCount={}", job.getTotalRunCount());
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testRetryJobStopsWithManager(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing retry job stops with manager ===");

        manager.start()
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> {
                ConsumerGroupRetryJob job = manager.getConsumerGroupRetryJob();
                assertNotNull(job, "Retry job should be running before stop");
                assertTrue(job.isRunning(), "Retry job should be running before stop");
                return manager.stop();
            })
            .compose(v -> {
                ConsumerGroupRetryJob job = manager.getConsumerGroupRetryJob();
                assertNull(job, "Retry job reference should be null after stop");
                assertFalse(manager.isStarted(), "Manager should not be started after stop");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testRetryJobDisabledByConfig(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing retry job disabled by config ===");

        // Override to disable retry job
        System.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        PeeGeeQConfiguration disabledConfig = new PeeGeeQConfiguration("test");

        // Close the manager from setUp and create a new one with retry disabled
        manager.closeReactive()
            .recover(t -> Future.succeededFuture())
            .compose(v -> {
                manager = new PeeGeeQManager(disabledConfig, new SimpleMeterRegistry());
                return manager.start();
            })
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> {
                ConsumerGroupRetryJob job = manager.getConsumerGroupRetryJob();
                assertNull(job, "Retry job should NOT be created when disabled");
                assertTrue(manager.isStarted(), "Manager should still start successfully");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}
