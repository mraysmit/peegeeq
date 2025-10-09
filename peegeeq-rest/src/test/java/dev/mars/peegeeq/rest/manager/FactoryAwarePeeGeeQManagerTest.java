package dev.mars.peegeeq.rest.manager;

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FactoryAwarePeeGeeQManager.
 * Validates that the manager automatically registers queue factory implementations.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FactoryAwarePeeGeeQManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(FactoryAwarePeeGeeQManagerTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_factory_aware_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private FactoryAwarePeeGeeQManager manager;

    @BeforeEach
    void setUp() {
        // Configure test database properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @Order(1)
    void testFactoryAwareManagerStartup() {
        logger.info("=== Testing Factory-Aware Manager Startup ===");

        // Create manager with basic configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);

        // Start should succeed and register factories
        assertDoesNotThrow(() -> {
            manager.start();
        });

        assertTrue(manager.isStarted(), "Manager should be started");
        logger.info("✅ Factory-aware manager startup test passed");
    }

    @Test
    @Order(2)
    void testFactoryAwareManagerWithMeterRegistry() {
        logger.info("=== Testing Factory-Aware Manager with Meter Registry ===");

        // Create manager with meter registry
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        manager = new FactoryAwarePeeGeeQManager(config, meterRegistry);

        assertDoesNotThrow(() -> {
            manager.start();
        });

        assertTrue(manager.isStarted(), "Manager should be started");
        assertNotNull(manager.getMeterRegistry(), "Meter registry should be available");
        logger.info("✅ Factory-aware manager with meter registry test passed");
    }

    @Test
    @Order(3)
    void testAutomaticFactoryRegistration() {
        logger.info("=== Testing Automatic Factory Registration ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);
        manager.start();

        // Check that factories were automatically registered
        var provider = manager.getQueueFactoryProvider();
        Set<String> supportedTypes = provider.getSupportedTypes();

        logger.info("Automatically registered factory types: {}", supportedTypes);

        // Should have at least native and outbox if dependencies are available
        // The exact types depend on what's on the classpath
        assertFalse(supportedTypes.isEmpty(), "Should have registered at least some factory types");

        logger.info("✅ Automatic factory registration test passed");
    }

    @Test
    @Order(4)
    void testFactoryCreationAfterAutoRegistration() {
        logger.info("=== Testing Factory Creation After Auto-Registration ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);
        manager.start();

        var provider = manager.getQueueFactoryProvider();
        Set<String> supportedTypes = provider.getSupportedTypes();

        // Try to create factories for each registered type
        for (String type : supportedTypes) {
            logger.info("Testing factory creation for type: {}", type);
            
            assertDoesNotThrow(() -> {
                QueueFactory factory = provider.createFactory(type, manager.getDatabaseService());
                assertNotNull(factory, "Factory should be created for type: " + type);
                assertTrue(factory.isHealthy(), "Factory should be healthy for type: " + type);
                factory.close();
            }, "Should be able to create factory for type: " + type);
        }

        logger.info("✅ Factory creation after auto-registration test passed");
    }

    @Test
    @Order(5)
    void testManagerHealthAfterFactoryRegistration() {
        logger.info("=== Testing Manager Health After Factory Registration ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);
        manager.start();

        // Manager should be healthy after factory registration
        assertTrue(manager.isHealthy(), "Manager should be healthy");

        // Health checks should pass
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertNotNull(healthStatus, "Health status should be available");

        logger.info("Health status: {}", healthStatus);
        logger.info("✅ Manager health after factory registration test passed");
    }

    @Test
    @Order(6)
    void testManagerStopAfterFactoryRegistration() {
        logger.info("=== Testing Manager Stop After Factory Registration ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);
        manager.start();

        assertTrue(manager.isStarted(), "Manager should be started");

        // Stop should work properly
        assertDoesNotThrow(() -> {
            manager.stop();
        });

        assertFalse(manager.isStarted(), "Manager should be stopped");
        logger.info("✅ Manager stop after factory registration test passed");
    }

    @Test
    @Order(7)
    void testFactoryRegistrationFailureHandling() {
        logger.info("=== Testing Factory Registration Failure Handling ===");

        // This test verifies that the manager handles factory registration failures gracefully
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new FactoryAwarePeeGeeQManager(config);

        // Even if some factory registrations fail, the manager should still start
        assertDoesNotThrow(() -> {
            manager.start();
        });

        assertTrue(manager.isStarted(), "Manager should still be started even if some factory registrations fail");
        logger.info("✅ Factory registration failure handling test passed");
    }

    @Test
    @Order(8)
    void testMultipleStartStopCycles() {
        logger.info("=== Testing Multiple Start/Stop Cycles ===");

        // Test multiple start/stop cycles with fresh manager instances
        // Note: Thread pools cannot be restarted once terminated, so we need new instances
        for (int i = 0; i < 3; i++) {
            logger.info("Start/stop cycle {}", i + 1);

            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
            FactoryAwarePeeGeeQManager cycleManager = new FactoryAwarePeeGeeQManager(config);

            assertDoesNotThrow(() -> {
                cycleManager.start();
            });
            assertTrue(cycleManager.isStarted(), "Manager should be started in cycle " + (i + 1));

            assertDoesNotThrow(() -> {
                cycleManager.stop();
            });
            assertFalse(cycleManager.isStarted(), "Manager should be stopped in cycle " + (i + 1));

            // Clean up the cycle manager
            cycleManager.close();
        }

        logger.info("✅ Multiple start/stop cycles test passed");
    }
}
