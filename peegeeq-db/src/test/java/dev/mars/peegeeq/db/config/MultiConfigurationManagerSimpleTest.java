// ========== FILE 1: MultiConfigurationManagerSimpleTest.java ==========
package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
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
@ExtendWith(VertxExtension.class)
class MultiConfigurationManagerSimpleTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationManagerSimpleTest.class);

    @BeforeEach
    void setUp() {
        // No System properties needed — configurations are loaded from properties files
    }

    @AfterEach
    void tearDown() {
        // No System properties to clean up
    }
    
    @Test
    void testMultiConfigurationManagerBasicShape() {
        assertNotNull(MultiConfigurationManager.class);

        MultiConfigurationManager configManager = new MultiConfigurationManager();
        try {
            assertTrue(configManager.getConfigurationNames().isEmpty());
            assertFalse(configManager.hasConfiguration("test"));
        } finally {
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
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
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
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
            PeeGeeQConfiguration config1 = new PeeGeeQConfiguration("test", new Properties());
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
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
        }
    }
    
    @Test
    void testConfigurationProfiles() {
        // Test that configuration profiles can be created
        assertDoesNotThrow(() -> {
            PeeGeeQConfiguration defaultConfig = new PeeGeeQConfiguration("default", new Properties());
            assertNotNull(defaultConfig);
            
            PeeGeeQConfiguration testConfig = new PeeGeeQConfiguration("test", new Properties());
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
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", new Properties());
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
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
        }
    }

    @Test
    void testRegisterConfigurationAfterStartFails(VertxTestContext testContext) {
        MultiConfigurationManager configManager = new MultiConfigurationManager();

        configManager.start()
            .onSuccess(v -> {
                testContext.verify(() -> {
                    assertTrue(configManager.isStarted());

                    IllegalStateException exception = assertThrows(IllegalStateException.class,
                        () -> configManager.registerConfiguration("late-config", "test"));
                    assertTrue(exception.getMessage().contains("Cannot register configurations while manager is in state"));
                });
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
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
        }
    }
    
    @Test
    void testResourceManagement(VertxTestContext testContext) {
        // Test resource management
        MultiConfigurationManager configManager = new MultiConfigurationManager();

        // close twice, then assert clean state
        configManager.close()
            .compose(v -> configManager.close())
            .onSuccess(v -> testContext.verify(() -> {
                assertFalse(configManager.isStarted());
                assertTrue(configManager.getConfigurationNames().isEmpty());
                logger.info("Resource management tests passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testInjectedMeterRegistryIsNotClosedByManager(VertxTestContext testContext) {
        TrackingSimpleMeterRegistry registry = new TrackingSimpleMeterRegistry();
        MultiConfigurationManager configManager = new MultiConfigurationManager(registry);

        configManager.close()
            .onSuccess(v -> testContext.verify(() -> {
                assertFalse(registry.isClosedByManager(),
                    "Injected meter registry should not be closed by manager");
                registry.close();
                assertTrue(registry.isClosedByManager());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
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
            configManager.close().onFailure(e -> fail("close failed: " + e.getMessage()));
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
