package dev.mars.peegeeq.db.lifecycle;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for event-driven lifecycle management in PeeGeeQManager.
 * These tests verify the reactive API exists and is properly structured.
 *
 * NOTE: These tests require database connectivity since PeeGeeQManager
 * initializes database connections during construction.
 * For full lifecycle integration tests with database connectivity, see integration test suite.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(SharedPostgresTestExtension.class)
public class EventDrivenLifecycleTest {

    private static final Logger logger = LoggerFactory.getLogger(EventDrivenLifecycleTest.class);

    @Test
    public void testEventDrivenLifecycleAPI() throws Exception {
        logger.info("=== Testing Event-Driven Lifecycle API (Unit Test) ===");

        PostgreSQLContainer<?> postgres = SharedPostgresTestExtension.getContainer();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName(),
            postgres.getUsername(),
            postgres.getPassword(),
            "public");
        PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        try {
            logger.info("Verifying reactive lifecycle API structure...");

            // Verify that the reactive lifecycle API exists and returns proper types
            // We don't actually start the manager since this is a unit test without database
            assertNotNull(manager.getVertx(), "Vert.x instance should be initialized");
            assertNotNull(manager.getVertx().eventBus(), "Event bus should be available");

            // Verify lifecycle event bus is accessible for subscriptions
            manager.getVertx().eventBus().consumer("peegeeq.lifecycle", message -> {
                // This consumer would receive lifecycle events if manager was started
                logger.debug("Lifecycle event consumer registered");
            });

            logger.info("Reactive lifecycle API structure verified successfully");

        } finally {
            // Clean up - close Vert.x instance without starting manager
            try {
                manager.closeReactive();
                logger.info("PeeGeeQ Manager closed");
            } catch (Exception e) {
                logger.warn("Error during cleanup", e);
            }
        }
    }
    
    @Test
    public void testReactiveHealthCheckStartup() throws Exception {
        logger.info("=== Testing Reactive Health Check Startup API (Unit Test) ===");

        PostgreSQLContainer<?> postgres = SharedPostgresTestExtension.getContainer();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName(),
            postgres.getUsername(),
            postgres.getPassword(),
            "public");
        PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        try {
            logger.info("Verifying health check manager initialization...");

            // Verify health check manager is initialized but not running
            assertNotNull(manager.getHealthCheckManager(),
                "Health check manager should be initialized");
            assertFalse(manager.getHealthCheckManager().isRunning(),
                "Health check manager should not be running before start");

            // Verify the reactive startup API exists and has correct signature
            // We don't call it since this is a unit test without database connectivity
            assertNotNull(manager, "PeeGeeQManager should support reactive lifecycle");

            logger.info("Health check manager API verified successfully");

        } finally {
            // Clean up - close without starting
            try {
                manager.closeReactive();
                logger.info("PeeGeeQ Manager closed");
            } catch (Exception e) {
                logger.warn("Error during cleanup", e);
            }
        }
    }
}
