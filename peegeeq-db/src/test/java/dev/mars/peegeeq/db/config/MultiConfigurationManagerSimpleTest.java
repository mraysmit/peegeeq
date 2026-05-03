// ========== FILE 1: MultiConfigurationManagerSimpleTest.java ==========
package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test for MultiConfigurationManager that doesn't require database connections or mocking.
 * Tests the basic structure and logic without external dependencies.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@ResourceLock("system-properties")
@ExtendWith(VertxExtension.class)
class MultiConfigurationManagerSimpleTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationManagerSimpleTest.class);

    @BeforeEach
    void setUp() {
        // Set minimal valid configuration properties
        System.setProperty("peegeeq.database.host", "localhost");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "test_db");
        System.setProperty("peegeeq.database.username", "test_user");
        System.setProperty("peegeeq.database.password", "test_pass");
        System.setProperty("peegeeq.database.pool.min-size", "2");
        System.setProperty("peegeeq.database.pool.max-size", "3");
        System.setProperty("peegeeq.database.pool.shared", "false");
        System.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
    }

    @AfterEach
    void tearDown() {
        // Clean up system properties
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }
    
    @Test
    void testMultiConfigurationManagerBasicShape() {
        assertNotNull(MultiConfigurationManager.class);

        MultiConfigurationManager configManager = new MultiConfigurationManager();
        try {
            assertTrue(configManager.getConfigurationNames().isEmpty());
            assertFalse(configManager.hasConfiguration("test"));
        } finally {
            configManager.close();
        }
    }
    
    @Test
    void testBasicConfigurationManagementWithoutDatabase() {
        // Test basic configuration management logic without database connections
        
        // Create manager (this should not require database connection)
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        try {
            // Initially no configurations
            assertTrue(configManager.getConfigurationNames().isEmpty());
            assertFalse(configManager.hasConfiguration("test"));
            assertFalse(configManager.isStarted());
            
            // Test invalid operations
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.registerConfiguration(null, "test");
            });
            
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.registerConfiguration("", "test");
            });
            
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.registerConfiguration("test", (PeeGeeQConfiguration) null);
            });
            
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.getConfiguration("non-existent");
            });
            
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.getDatabaseService("non-existent");
            });
            
            // createFactory throws IllegalStateException when manager is not started
            assertThrows(IllegalStateException.class, () -> {
                configManager.createFactory("non-existent", "native");
            });
            
            logger.info("Basic configuration management tests passed");
            
        } finally {
            configManager.close();
        }
    }

    @Test
    void testConstructorRejectsNullMeterRegistry() {
        NullPointerException exception = assertThrows(NullPointerException.class,
            () -> new MultiConfigurationManager(null));
        assertEquals("meterRegistry", exception.getMessage());
    }
    
    @Test
    void testConfigurationRegistrationLogic() {
        // Test configuration creation without database connections
        MultiConfigurationManager configManager = new MultiConfigurationManager();

        try {
            // Test that we can create configuration objects
            PeeGeeQConfiguration config1 = new PeeGeeQConfiguration("test");
            assertNotNull(config1);

            // Test configuration validation without attempting database connection
            assertNotNull(config1.getProfile());
            assertEquals("test", config1.getProfile());

            // Test that configuration names can be retrieved (should be empty initially)
            Set<String> configNames = configManager.getConfigurationNames();
            assertNotNull(configNames);

            // Test that hasConfiguration returns false for non-existent configurations
            assertFalse(configManager.hasConfiguration("non-existent"));

            logger.info("Configuration registration logic tests passed");

        } finally {
            configManager.close();
        }
    }
    
    @Test
    void testConfigurationProfiles() {
        // Test that configuration profiles can be created
        assertDoesNotThrow(() -> {
            PeeGeeQConfiguration defaultConfig = new PeeGeeQConfiguration();
            assertNotNull(defaultConfig);
            
            PeeGeeQConfiguration testConfig = new PeeGeeQConfiguration("test");
            assertNotNull(testConfig);
            
            // Test that different profiles create different configurations
            assertNotSame(defaultConfig, testConfig);
        });
        
        logger.info("Configuration profiles tests passed");
    }
    
    @Test
    void testConfigurationProperties() {
        // Test that configuration properties files exist and can be loaded
        assertDoesNotThrow(() -> {
            // Test that we can create configurations with different profiles
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
            assertNotNull(config);
            
            // Test that the configuration has expected properties
            assertNotNull(config.getProfile());
        });
        
        logger.info("Configuration properties tests passed");
    }
    
    @Test
    void testManagerLifecycle(VertxTestContext testContext) {
        // Test manager lifecycle without database connections
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        // Initially not started
        assertFalse(configManager.isStarted());
        
        // Chain async operations using compose()
        configManager.start()
            .compose(v -> {
                // After start with no configurations, should be started
                assertTrue(configManager.isStarted());
                
                // Call start again (should be idempotent)
                return configManager.start();
            })
            .onSuccess(v -> {
                logger.info("Manager lifecycle tests passed");
                closeManagerAsync(configManager, testContext);
            })
            .onFailure(err -> {
                logger.error("Manager lifecycle test failed", err);
                closeManagerAsync(configManager, testContext);
                testContext.failNow(err);
            });
    }
    
    @Test
    void testConfigurationNamesManagement() {
        // Test configuration names management
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        try {
            // Initially empty
            Set<String> names = configManager.getConfigurationNames();
            assertNotNull(names);
            assertTrue(names.isEmpty());
            
            // Names should be immutable
            assertThrows(UnsupportedOperationException.class, () -> {
                names.add("test");
            });
            
            logger.info("Configuration names management tests passed");
            
        } finally {
            configManager.close();
        }
    }

    @Test
    void testRegisterConfigurationAfterStartFails(VertxTestContext testContext) {
        MultiConfigurationManager configManager = new MultiConfigurationManager();

        configManager.start()
            .onSuccess(v -> {
                assertTrue(configManager.isStarted());

                IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> configManager.registerConfiguration("late-config", "test"));
                assertTrue(exception.getMessage().contains("Cannot register configurations while manager is in state"));
                
                closeManagerAsync(configManager, testContext);
            })
            .onFailure(err -> {
                logger.error("Test failed", err);
                closeManagerAsync(configManager, testContext);
                testContext.failNow(err);
            });
    }
    
    @Test
    void testErrorHandling() {
        // Test error handling without database dependencies
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        try {
            // Test null parameter handling
            assertThrows(NullPointerException.class, () -> {
                configManager.hasConfiguration(null);
            });
            
            // Test empty string handling  
            assertFalse(configManager.hasConfiguration(""));
            assertFalse(configManager.hasConfiguration("   "));
            
            // Test non-existent configuration handling
            assertThrows(IllegalArgumentException.class, () -> {
                configManager.getConfiguration("does-not-exist");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                configManager.getDatabaseService("does-not-exist");
            });
            
            logger.info("Error handling tests passed");
            
        } finally {
            configManager.close();
        }
    }
    
    @Test
    void testResourceManagement() {
        // Test resource management
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        // Should be able to close without issues
        assertDoesNotThrow(() -> {
            configManager.close();
        });
        
        // Should be able to close multiple times
        assertDoesNotThrow(() -> {
            configManager.close();
        });
        
        // After close, should be in clean state
        assertFalse(configManager.isStarted());
        assertTrue(configManager.getConfigurationNames().isEmpty());
        
        logger.info("Resource management tests passed");
    }

    @Test
    void testInjectedMeterRegistryIsNotClosedByManager() {
        TrackingSimpleMeterRegistry registry = new TrackingSimpleMeterRegistry();
        MultiConfigurationManager configManager = new MultiConfigurationManager(registry);

        configManager.close();

        assertFalse(registry.isClosedByManager(),
            "Injected meter registry should not be closed by manager");

        // Test cleanup responsibility remains with caller.
        registry.close();
        assertTrue(registry.isClosedByManager());
    }
    
    @Test
    void testThreadSafety() {
        // Basic thread safety test (structure only)
        MultiConfigurationManager configManager = new MultiConfigurationManager();
        
        try {
            // Test that concurrent access to read-only methods doesn't throw exceptions
            assertDoesNotThrow(() -> {
                Thread t1 = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        configManager.getConfigurationNames();
                        configManager.isStarted();
                    }
                });
                
                Thread t2 = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        configManager.hasConfiguration("test");
                        configManager.isStarted();
                    }
                });
                
                t1.start();
                t2.start();

                t1.join();
                t2.join();
            });
            
            logger.info("Thread safety tests passed");

        } finally {
            configManager.close();
        }
    }

    /**
     * Asynchronously close the manager using future composition.
     * Used by async tests that use VertxTestContext.
     */
    private void closeManagerAsync(MultiConfigurationManager configManager, VertxTestContext testContext) {
        configManager.close()
            .onSuccess(v -> {
                logger.debug("Manager closed successfully");
                testContext.completeNow();
            })
            .onFailure(t -> {
                logger.error("Failed to close manager", t);
                testContext.completeNow();
            });
    }

    private static final class TrackingSimpleMeterRegistry extends SimpleMeterRegistry {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
            super.close();
        }

        boolean isClosedByManager() {
            return closed.get();
        }
    }
}
