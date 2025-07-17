package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Test class for MultiConfigurationManager functionality.
 * Uses mocking to avoid database dependencies in unit tests.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class MultiConfigurationManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationManagerTest.class);

    private MultiConfigurationManager configManager;
    private AutoCloseable mockitoCloseable;

    @Mock
    private PeeGeeQManager mockManager;

    @Mock
    private QueueFactory mockQueueFactory;

    @Mock
    private DatabaseService mockDatabaseService;

    @BeforeEach
    void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        configManager = new MultiConfigurationManager(new SimpleMeterRegistry());

        // Configure mock behavior
        when(mockQueueFactory.getImplementationType()).thenReturn("native");
        when(mockQueueFactory.isHealthy()).thenReturn(true);
        try {
            doNothing().when(mockQueueFactory).close();
            doNothing().when(mockManager).close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure mock", e);
        }
        doNothing().when(mockManager).start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (configManager != null) {
            configManager.close();
        }
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }
    
    @Test
    void testRegisterConfiguration() {
        // Mock PeeGeeQManager constructor to avoid database connection
        try (MockedStatic<PeeGeeQManager> mockedManager = mockStatic(PeeGeeQManager.class)) {
            mockedManager.when(() -> new PeeGeeQManager(any(PeeGeeQConfiguration.class), any()))
                        .thenReturn(mockManager);

            // Test registering a configuration
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
            configManager.registerConfiguration("test-config", config);

            // Verify configuration is registered
            assertTrue(configManager.hasConfiguration("test-config"));
            assertEquals(Set.of("test-config"), configManager.getConfigurationNames());

            // Verify we can retrieve the configuration
            PeeGeeQConfiguration retrievedConfig = configManager.getConfiguration("test-config");
            assertNotNull(retrievedConfig);
        }
    }
    
    @Test
    void testRegisterConfigurationWithProfile() {
        // Mock PeeGeeQManager constructor to avoid database connection
        try (MockedStatic<PeeGeeQManager> mockedManager = mockStatic(PeeGeeQManager.class)) {
            mockedManager.when(() -> new PeeGeeQManager(any(PeeGeeQConfiguration.class), any()))
                        .thenReturn(mockManager);

            // Test registering a configuration using profile name
            configManager.registerConfiguration("dev-config", "test");

            // Verify configuration is registered
            assertTrue(configManager.hasConfiguration("dev-config"));
            assertNotNull(configManager.getConfiguration("dev-config"));
        }
    }
    
    @Test
    void testRegisterDuplicateConfiguration() {
        // Mock PeeGeeQManager constructor to avoid database connection
        try (MockedStatic<PeeGeeQManager> mockedManager = mockStatic(PeeGeeQManager.class)) {
            mockedManager.when(() -> new PeeGeeQManager(any(PeeGeeQConfiguration.class), any()))
                        .thenReturn(mockManager);

            // Register first configuration
            configManager.registerConfiguration("duplicate", "test");

            // Attempt to register duplicate should throw exception
            assertThrows(IllegalStateException.class, () -> {
                configManager.registerConfiguration("duplicate", "test");
            });
        }
    }
    
    @Test
    void testRegisterInvalidConfiguration() {
        // Test null name
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.registerConfiguration(null, new PeeGeeQConfiguration());
        });
        
        // Test empty name
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.registerConfiguration("", new PeeGeeQConfiguration());
        });
        
        // Test null configuration
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.registerConfiguration("test", (PeeGeeQConfiguration) null);
        });
    }
    
    @Test
    void testMultipleConfigurations() {
        // Mock PeeGeeQManager constructor to avoid database connection
        try (MockedStatic<PeeGeeQManager> mockedManager = mockStatic(PeeGeeQManager.class)) {
            mockedManager.when(() -> new PeeGeeQManager(any(PeeGeeQConfiguration.class), any()))
                        .thenReturn(mockManager);

            // Register multiple configurations
            configManager.registerConfiguration("high-throughput", "test");
            configManager.registerConfiguration("low-latency", "test");
            configManager.registerConfiguration("reliable", "test");

            // Verify all configurations are registered
            Set<String> configNames = configManager.getConfigurationNames();
            assertEquals(3, configNames.size());
            assertTrue(configNames.contains("high-throughput"));
            assertTrue(configNames.contains("low-latency"));
            assertTrue(configNames.contains("reliable"));

            // Verify each configuration can be retrieved
            assertNotNull(configManager.getConfiguration("high-throughput"));
            assertNotNull(configManager.getConfiguration("low-latency"));
            assertNotNull(configManager.getConfiguration("reliable"));
        }
    }
    
    @Test
    void testStartConfigurations() {
        // Mock PeeGeeQManager constructor to avoid database connection
        try (MockedStatic<PeeGeeQManager> mockedManager = mockStatic(PeeGeeQManager.class)) {
            mockedManager.when(() -> new PeeGeeQManager(any(PeeGeeQConfiguration.class), any()))
                        .thenReturn(mockManager);

            // Register configurations
            configManager.registerConfiguration("config1", "test");
            configManager.registerConfiguration("config2", "test");

            // Start configurations
            assertDoesNotThrow(() -> configManager.start());
            assertTrue(configManager.isStarted());

            // Starting again should not throw exception
            assertDoesNotThrow(() -> configManager.start());
        }
    }
    
    @Test
    void testCreateFactory() throws Exception {
        // Register configuration and start
        configManager.registerConfiguration("test-config", "development");
        configManager.start();

        // Create factory
        QueueFactory factory = configManager.createFactory("test-config", "native");
        assertNotNull(factory);
        assertEquals("native", factory.getImplementationType());

        factory.close();
    }

    @Test
    void testCreateFactoryWithAdditionalConfig() throws Exception {
        // Register configuration and start
        configManager.registerConfiguration("test-config", "development");
        configManager.start();

        // Create factory with additional configuration
        Map<String, Object> additionalConfig = Map.of(
            "batch-size", 50,
            "polling-interval", "PT0.5S"
        );

        QueueFactory factory = configManager.createFactory("test-config", "native", additionalConfig);
        assertNotNull(factory);
        assertEquals("native", factory.getImplementationType());

        factory.close();
    }
    
    @Test
    void testCreateFactoryWithNonExistentConfiguration() {
        // Attempt to create factory with non-existent configuration
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.createFactory("non-existent", "native");
        });
    }
    
    @Test
    void testGetDatabaseService() {
        // Register configuration
        configManager.registerConfiguration("test-config", "development");
        
        // Get database service
        DatabaseService databaseService = configManager.getDatabaseService("test-config");
        assertNotNull(databaseService);
    }
    
    @Test
    void testGetDatabaseServiceNonExistent() {
        // Attempt to get database service for non-existent configuration
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.getDatabaseService("non-existent");
        });
    }
    
    @Test
    void testGetConfigurationNonExistent() {
        // Attempt to get non-existent configuration
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.getConfiguration("non-existent");
        });
    }
    
    @Test
    void testClose() {
        // Register configurations and start
        configManager.registerConfiguration("config1", "development");
        configManager.registerConfiguration("config2", "development");
        configManager.start();
        
        assertTrue(configManager.isStarted());
        
        // Close manager
        configManager.close();
        
        // Verify state is reset
        assertFalse(configManager.isStarted());
        assertTrue(configManager.getConfigurationNames().isEmpty());
    }
    
    @Test
    void testConfigurationIsolation() throws Exception {
        // Register different configurations
        configManager.registerConfiguration("high-throughput", "high-throughput");
        configManager.registerConfiguration("low-latency", "low-latency");
        configManager.start();

        // Create factories from different configurations
        QueueFactory highThroughputFactory = configManager.createFactory("high-throughput", "native");
        QueueFactory lowLatencyFactory = configManager.createFactory("low-latency", "native");

        // Verify they are different instances
        assertNotSame(highThroughputFactory, lowLatencyFactory);

        // Both should be healthy
        assertTrue(highThroughputFactory.isHealthy());
        assertTrue(lowLatencyFactory.isHealthy());

        highThroughputFactory.close();
        lowLatencyFactory.close();
    }
}
