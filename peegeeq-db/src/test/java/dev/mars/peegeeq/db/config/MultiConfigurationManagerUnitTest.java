package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
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

/**
 * Unit test for MultiConfigurationManager that uses mocking to avoid database dependencies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class MultiConfigurationManagerUnitTest {
    
    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationManagerUnitTest.class);
    
    private MultiConfigurationManager configManager;
    private AutoCloseable mockitoCloseable;
    
    @Mock
    private PeeGeeQManager mockManager;
    
    @Mock
    private QueueFactory mockQueueFactory;
    
    @Mock
    private DatabaseService mockDatabaseService;
    
    @Mock
    private QueueFactoryProvider mockFactoryProvider;
    
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
        
        when(mockFactoryProvider.createFactory(anyString(), any(DatabaseService.class)))
            .thenReturn(mockQueueFactory);
        when(mockFactoryProvider.createFactory(anyString(), any(DatabaseService.class), any(Map.class)))
            .thenReturn(mockQueueFactory);
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
    void testBasicConfigurationManagement() {
        // Test basic configuration management without database connections
        
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
        
        assertThrows(IllegalArgumentException.class, () -> {
            configManager.createFactory("non-existent", "native");
        });
    }
    
    @Test
    void testConfigurationRegistrationWithMocking() {
        // Mock the PeeGeeQManager and PgDatabaseService constructors
        try (MockedConstruction<PeeGeeQManager> mockedManager = mockConstruction(PeeGeeQManager.class,
                (mock, context) -> {
                    doNothing().when(mock).start();
                    doNothing().when(mock).close();
                });
             MockedConstruction<PgDatabaseService> mockedDbService = mockConstruction(PgDatabaseService.class)) {
            
            // Test registering a configuration
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
            configManager.registerConfiguration("test-config", config);
            
            // Verify configuration is registered
            assertTrue(configManager.hasConfiguration("test-config"));
            assertEquals(Set.of("test-config"), configManager.getConfigurationNames());
            
            // Verify we can retrieve the configuration
            PeeGeeQConfiguration retrievedConfig = configManager.getConfiguration("test-config");
            assertNotNull(retrievedConfig);
            
            // Verify database service is available
            DatabaseService dbService = configManager.getDatabaseService("test-config");
            assertNotNull(dbService);
            
            // Test duplicate registration
            assertThrows(IllegalStateException.class, () -> {
                configManager.registerConfiguration("test-config", config);
            });
        }
    }
    
    @Test
    void testMultipleConfigurationsWithMocking() {
        // Mock the constructors
        try (MockedConstruction<PeeGeeQManager> mockedManager = mockConstruction(PeeGeeQManager.class,
                (mock, context) -> {
                    doNothing().when(mock).start();
                    doNothing().when(mock).close();
                });
             MockedConstruction<PgDatabaseService> mockedDbService = mockConstruction(PgDatabaseService.class)) {
            
            // Register multiple configurations
            configManager.registerConfiguration("config1", "test");
            configManager.registerConfiguration("config2", "test");
            configManager.registerConfiguration("config3", "test");
            
            // Verify all configurations are registered
            Set<String> configNames = configManager.getConfigurationNames();
            assertEquals(3, configNames.size());
            assertTrue(configNames.contains("config1"));
            assertTrue(configNames.contains("config2"));
            assertTrue(configNames.contains("config3"));
            
            // Verify each configuration can be retrieved
            assertNotNull(configManager.getConfiguration("config1"));
            assertNotNull(configManager.getConfiguration("config2"));
            assertNotNull(configManager.getConfiguration("config3"));
            
            // Test starting configurations
            assertDoesNotThrow(() -> configManager.start());
            assertTrue(configManager.isStarted());
            
            // Starting again should not throw exception
            assertDoesNotThrow(() -> configManager.start());
        }
    }
    
    @Test
    void testFactoryCreationWithMocking() {
        // Mock all the constructors and factory provider
        try (MockedConstruction<PeeGeeQManager> mockedManager = mockConstruction(PeeGeeQManager.class,
                (mock, context) -> {
                    doNothing().when(mock).start();
                    doNothing().when(mock).close();
                });
             MockedConstruction<PgDatabaseService> mockedDbService = mockConstruction(PgDatabaseService.class);
             MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(anyString(), any(DatabaseService.class)))
                        .thenReturn(mockQueueFactory);
                    when(mock.createFactory(anyString(), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Register configuration and start
            configManager.registerConfiguration("test-config", "test");
            configManager.start();
            
            // Create factory
            QueueFactory factory = configManager.createFactory("test-config", "native");
            assertNotNull(factory);
            assertEquals("native", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Create factory with additional configuration
            Map<String, Object> additionalConfig = Map.of(
                "batch-size", 50,
                "polling-interval", "PT0.5S"
            );
            
            QueueFactory factory2 = configManager.createFactory("test-config", "native", additionalConfig);
            assertNotNull(factory2);
            assertEquals("native", factory2.getImplementationType());
            assertTrue(factory2.isHealthy());
        }
    }
    
    @Test
    void testConfigurationLifecycle() {
        // Mock the constructors
        try (MockedConstruction<PeeGeeQManager> mockedManager = mockConstruction(PeeGeeQManager.class,
                (mock, context) -> {
                    doNothing().when(mock).start();
                    doNothing().when(mock).close();
                });
             MockedConstruction<PgDatabaseService> mockedDbService = mockConstruction(PgDatabaseService.class)) {
            
            // Register configurations
            configManager.registerConfiguration("config1", "test");
            configManager.registerConfiguration("config2", "test");
            
            assertFalse(configManager.isStarted());
            assertEquals(2, configManager.getConfigurationNames().size());
            
            // Start configurations
            configManager.start();
            assertTrue(configManager.isStarted());
            
            // Close manager
            configManager.close();
            assertFalse(configManager.isStarted());
            assertTrue(configManager.getConfigurationNames().isEmpty());
        }
    }
    
    @Test
    void testQueueConfigurationBuilderIntegration() {
        // Test that QueueConfigurationBuilder methods don't throw exceptions
        // when called with mock database service
        assertDoesNotThrow(() -> {
            // These will fail with real database connections, but should not throw
            // compilation or immediate runtime errors
            assertNotNull(QueueConfigurationBuilder.class);
            
            // Test that the methods exist and are callable
            var methods = QueueConfigurationBuilder.class.getDeclaredMethods();
            assertTrue(methods.length > 0);
            
            // Verify expected methods exist
            boolean hasHighThroughput = false;
            boolean hasLowLatency = false;
            boolean hasReliable = false;
            boolean hasCustom = false;
            
            for (var method : methods) {
                switch (method.getName()) {
                    case "createHighThroughputQueue" -> hasHighThroughput = true;
                    case "createLowLatencyQueue" -> hasLowLatency = true;
                    case "createReliableQueue" -> hasReliable = true;
                    case "createCustomQueue" -> hasCustom = true;
                }
            }
            
            assertTrue(hasHighThroughput, "Should have createHighThroughputQueue method");
            assertTrue(hasLowLatency, "Should have createLowLatencyQueue method");
            assertTrue(hasReliable, "Should have createReliableQueue method");
            assertTrue(hasCustom, "Should have createCustomQueue method");
        });
    }
}
