package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.test.MockQueueFactory;
import dev.mars.peegeeq.db.test.TestFactoryRegistration;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the queue factory registration system.
 * Validates that queue factories can be registered and unregistered properly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QueueFactoryRegistrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueFactoryRegistrationTest.class);

    private QueueFactoryRegistrar registrar;

    @BeforeEach
    void setUp() {
        // Get the registrar from the manager that BaseIntegrationTest already set up
        registrar = manager.getQueueFactoryRegistrar();
        assertNotNull(registrar, "Queue factory registrar should be available");
    }

    @Test
    @Order(1)
    void testBasicFactoryRegistration() {
        logger.info("=== Testing Basic Factory Registration ===");

        // Register a mock factory
        registrar.registerFactory("test-factory", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });

        // Verify it's registered
        var provider = manager.getQueueFactoryProvider();
        Set<String> supportedTypes = provider.getSupportedTypes();
        assertTrue(supportedTypes.contains("test-factory"), "Test factory should be registered");

        // Create factory instance
        assertDoesNotThrow(() -> {
            QueueFactory factory = provider.createFactory("test-factory", manager.getDatabaseService());
            assertNotNull(factory, "Factory should be created successfully");
            factory.close();
        });

        logger.info("✅ Basic factory registration test passed");
    }

    @Test
    @Order(2)
    void testFactoryUnregistration() {
        logger.info("=== Testing Factory Unregistration ===");

        // Register a factory
        registrar.registerFactory("temp-factory", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });

        var provider = manager.getQueueFactoryProvider();
        assertTrue(provider.getSupportedTypes().contains("temp-factory"));

        // Unregister it
        registrar.unregisterFactory("temp-factory");

        // Verify it's no longer available
        assertFalse(provider.getSupportedTypes().contains("temp-factory"));

        // Attempting to create should fail
        assertThrows(Exception.class, () -> {
            provider.createFactory("temp-factory", manager.getDatabaseService());
        });

        logger.info("✅ Factory unregistration test passed");
    }

    @Test
    @Order(3)
    void testMultipleFactoryRegistration() {
        logger.info("=== Testing Multiple Factory Registration ===");

        // Register multiple factories
        registrar.registerFactory("factory-1", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });
        registrar.registerFactory("factory-2", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });
        registrar.registerFactory("factory-3", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });

        var provider = manager.getQueueFactoryProvider();
        Set<String> supportedTypes = provider.getSupportedTypes();

        assertTrue(supportedTypes.contains("factory-1"));
        assertTrue(supportedTypes.contains("factory-2"));
        assertTrue(supportedTypes.contains("factory-3"));

        // Create instances of each
        assertDoesNotThrow(() -> {
            try (QueueFactory f1 = provider.createFactory("factory-1", manager.getDatabaseService());
                 QueueFactory f2 = provider.createFactory("factory-2", manager.getDatabaseService());
                 QueueFactory f3 = provider.createFactory("factory-3", manager.getDatabaseService())) {
                
                assertNotNull(f1);
                assertNotNull(f2);
                assertNotNull(f3);
            }
        });

        logger.info("✅ Multiple factory registration test passed");
    }

    @Test
    @Order(4)
    void testFactoryRegistrationWithConfiguration() {
        logger.info("=== Testing Factory Registration with Configuration ===");

        // Register factory that uses configuration
        registrar.registerFactory("config-factory", (databaseService, configuration) -> {
            MockQueueFactory factory = new MockQueueFactory(databaseService, configuration);
            // Verify configuration is passed through
            assertNotNull(configuration, "Configuration should be provided");
            return factory;
        });

        var provider = manager.getQueueFactoryProvider();
        
        // Create with configuration
        assertDoesNotThrow(() -> {
            QueueFactory factory = provider.createFactory("config-factory", manager.getDatabaseService());
            assertNotNull(factory);
            factory.close();
        });

        logger.info("✅ Factory registration with configuration test passed");
    }

    @Test
    @Order(5)
    void testAvailableFactoriesRegistration() {
        logger.info("=== Testing Available Factories Registration ===");

        // Use the test utility to register available factories
        TestFactoryRegistration.registerAvailableFactories(registrar);

        var provider = manager.getQueueFactoryProvider();
        Set<String> supportedTypes = provider.getSupportedTypes();

        // Should at least have mock factory
        assertTrue(supportedTypes.contains("mock"), "Mock factory should be registered");

        // May have native and outbox if available on classpath
        logger.info("Available factory types: {}", supportedTypes);

        // Test creating mock factory
        assertDoesNotThrow(() -> {
            QueueFactory factory = provider.createFactory("mock", manager.getDatabaseService());
            assertNotNull(factory);
            assertTrue(factory.isHealthy());
            factory.close();
        });

        logger.info("✅ Available factories registration test passed");
    }

    @Test
    @Order(6)
    void testDuplicateFactoryRegistration() {
        logger.info("=== Testing Duplicate Factory Registration ===");

        // Register a factory
        registrar.registerFactory("duplicate-test", (databaseService, configuration) -> {
            return new MockQueueFactory(databaseService, configuration);
        });

        // Register again with same name - should replace
        registrar.registerFactory("duplicate-test", (databaseService, configuration) -> {
            // Add a marker in configuration to distinguish this factory
            Map<String, Object> modifiedConfig = new HashMap<>(configuration);
            modifiedConfig.put("test_marker", "replaced");
            return new MockQueueFactory(databaseService, modifiedConfig);
        });

        var provider = manager.getQueueFactoryProvider();
        assertTrue(provider.getSupportedTypes().contains("duplicate-test"));

        // Should only have one instance
        assertEquals(1, provider.getSupportedTypes().stream()
                .filter(type -> type.equals("duplicate-test"))
                .count());

        logger.info("✅ Duplicate factory registration test passed");
    }

    /**
     * Tests error handling when a queue factory fails to create.
     * This test intentionally creates a failing factory to verify that the system
     * handles factory creation failures gracefully.
     *
     * NOTE: The ERROR log that appears during this test is EXPECTED and INTENTIONAL.
     */
    @Test
    @Order(7)
    void testFactoryCreationFailure() {
        logger.info("=== Testing Factory Creation Failure Handling ===");
        logger.info("NOTE: This test intentionally creates a failing factory to test error handling");
        logger.info("The following ERROR log is EXPECTED and part of the test validation");

        // Register a factory that throws exception
        registrar.registerFactory("failing-factory", (databaseService, configuration) -> {
            logger.info("INTENTIONAL TEST FAILURE: Throwing exception to test error handling");
            throw new RuntimeException("Intentional factory creation failure for testing error handling");
        });

        var provider = manager.getQueueFactoryProvider();
        assertTrue(provider.getSupportedTypes().contains("failing-factory"));

        // Creating should fail gracefully
        logger.info("About to test factory creation failure - the following ERROR is EXPECTED");
        assertThrows(Exception.class, () -> {
            provider.createFactory("failing-factory", manager.getDatabaseService());
        });

        logger.info("✅ Factory creation failure handling test passed - the ERROR above was intentional");
    }
}
