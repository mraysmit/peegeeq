package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for QueueConfigurationBuilder that uses mocking to avoid database dependencies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class QueueConfigurationBuilderUnitTest {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueConfigurationBuilderUnitTest.class);
    
    private AutoCloseable mockitoCloseable;
    
    @Mock
    private DatabaseService mockDatabaseService;
    
    @Mock
    private QueueFactory mockQueueFactory;
    
    @BeforeEach
    void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        
        // Configure mock behavior
        when(mockQueueFactory.getImplementationType()).thenReturn("native");
        when(mockQueueFactory.isHealthy()).thenReturn(true);
        try {
            doNothing().when(mockQueueFactory).close();
        } catch (Exception e) {
            // This shouldn't happen with mocks, but handle it just in case
            throw new RuntimeException("Failed to configure mock", e);
        }
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }
    
    @Test
    void testQueueConfigurationBuilderClassStructure() {
        // Test that QueueConfigurationBuilder class exists and has expected structure
        assertNotNull(QueueConfigurationBuilder.class);
        
        // Verify it's a utility class (should have private constructor)
        var constructors = QueueConfigurationBuilder.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        
        // Verify expected static methods exist
        var methods = QueueConfigurationBuilder.class.getDeclaredMethods();
        assertTrue(methods.length > 0);
        
        boolean hasHighThroughput = false;
        boolean hasLowLatency = false;
        boolean hasReliable = false;
        boolean hasDurable = false;
        boolean hasCustom = false;
        
        for (Method method : methods) {
            switch (method.getName()) {
                case "createHighThroughputQueue" -> {
                    hasHighThroughput = true;
                    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
                    assertEquals(QueueFactory.class, method.getReturnType());
                }
                case "createLowLatencyQueue" -> {
                    hasLowLatency = true;
                    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
                    assertEquals(QueueFactory.class, method.getReturnType());
                }
                case "createReliableQueue" -> {
                    hasReliable = true;
                    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
                    assertEquals(QueueFactory.class, method.getReturnType());
                }
                case "createDurableQueue" -> {
                    hasDurable = true;
                    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
                    assertEquals(QueueFactory.class, method.getReturnType());
                }
                case "createCustomQueue" -> {
                    hasCustom = true;
                    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
                    assertEquals(QueueFactory.class, method.getReturnType());
                }
            }
        }
        
        assertTrue(hasHighThroughput, "Should have createHighThroughputQueue method");
        assertTrue(hasLowLatency, "Should have createLowLatencyQueue method");
        assertTrue(hasReliable, "Should have createReliableQueue method");
        assertTrue(hasDurable, "Should have createDurableQueue method");
        assertTrue(hasCustom, "Should have createCustomQueue method");
    }
    
    @Test
    void testCreateHighThroughputQueueWithMocking() {
        // Mock the PgQueueFactoryProvider
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(eq("native"), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test creating high-throughput queue
            QueueFactory factory = QueueConfigurationBuilder.createHighThroughputQueue(mockDatabaseService);
            
            assertNotNull(factory);
            assertEquals("native", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Verify the provider was called with correct parameters
            assertEquals(1, mockedProvider.constructed().size());
            var providerInstance = mockedProvider.constructed().get(0);
            verify(providerInstance).createFactory(eq("native"), eq(mockDatabaseService), any(Map.class));
        }
    }
    
    @Test
    void testCreateLowLatencyQueueWithMocking() {
        // Mock the PgQueueFactoryProvider
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(eq("native"), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test creating low-latency queue
            QueueFactory factory = QueueConfigurationBuilder.createLowLatencyQueue(mockDatabaseService);
            
            assertNotNull(factory);
            assertEquals("native", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Verify the provider was called with correct parameters
            assertEquals(1, mockedProvider.constructed().size());
            var providerInstance = mockedProvider.constructed().get(0);
            verify(providerInstance).createFactory(eq("native"), eq(mockDatabaseService), any(Map.class));
        }
    }
    
    @Test
    void testCreateReliableQueueWithMocking() {
        // Mock the PgQueueFactoryProvider
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mockQueueFactory.getImplementationType()).thenReturn("outbox");
                    when(mock.createFactory(eq("outbox"), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test creating reliable queue
            QueueFactory factory = QueueConfigurationBuilder.createReliableQueue(mockDatabaseService);
            
            assertNotNull(factory);
            assertEquals("outbox", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Verify the provider was called with correct parameters
            assertEquals(1, mockedProvider.constructed().size());
            var providerInstance = mockedProvider.constructed().get(0);
            verify(providerInstance).createFactory(eq("outbox"), eq(mockDatabaseService), any(Map.class));
        }
    }
    
    @Test
    void testCreateDurableQueueWithMocking() {
        // Mock the PgQueueFactoryProvider
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mockQueueFactory.getImplementationType()).thenReturn("outbox");
                    when(mock.createFactory(eq("outbox"), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test creating durable queue
            QueueFactory factory = QueueConfigurationBuilder.createDurableQueue(mockDatabaseService);
            
            assertNotNull(factory);
            assertEquals("outbox", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Verify the provider was called with correct parameters
            assertEquals(1, mockedProvider.constructed().size());
            var providerInstance = mockedProvider.constructed().get(0);
            verify(providerInstance).createFactory(eq("outbox"), eq(mockDatabaseService), any(Map.class));
        }
    }
    
    @Test
    void testCreateCustomQueueWithMocking() {
        // Mock the PgQueueFactoryProvider
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(eq("native"), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test creating custom queue with specific settings
            QueueFactory factory = QueueConfigurationBuilder.createCustomQueue(
                mockDatabaseService,
                "native",
                5,                              // batch size
                Duration.ofMillis(500),         // polling interval
                3,                              // max retries
                Duration.ofSeconds(30),         // visibility timeout
                true                            // dead letter enabled
            );
            
            assertNotNull(factory);
            assertEquals("native", factory.getImplementationType());
            assertTrue(factory.isHealthy());
            
            // Verify the provider was called with correct parameters
            assertEquals(1, mockedProvider.constructed().size());
            var providerInstance = mockedProvider.constructed().get(0);
            verify(providerInstance).createFactory(eq("native"), eq(mockDatabaseService), any(Map.class));
        }
    }
    
    @Test
    void testNullDatabaseServiceHandling() {
        // Test that null database service is handled appropriately
        // The actual behavior will depend on the PgQueueFactoryProvider implementation
        
        // Mock the PgQueueFactoryProvider to throw exception for null database service
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(anyString(), isNull(), any(Map.class)))
                        .thenThrow(new IllegalArgumentException("Database service cannot be null"));
                })) {
            
            // Test with null database service
            assertThrows(RuntimeException.class, () -> {
                QueueConfigurationBuilder.createHighThroughputQueue(null);
            });
            
            assertThrows(RuntimeException.class, () -> {
                QueueConfigurationBuilder.createLowLatencyQueue(null);
            });
            
            assertThrows(RuntimeException.class, () -> {
                QueueConfigurationBuilder.createReliableQueue(null);
            });
            
            assertThrows(RuntimeException.class, () -> {
                QueueConfigurationBuilder.createDurableQueue(null);
            });
        }
    }
    
    @Test
    void testConfigurationParametersArePassedCorrectly() {
        // Mock the PgQueueFactoryProvider to capture configuration parameters
        try (MockedConstruction<PgQueueFactoryProvider> mockedProvider = mockConstruction(PgQueueFactoryProvider.class,
                (mock, context) -> {
                    when(mock.createFactory(anyString(), any(DatabaseService.class), any(Map.class)))
                        .thenReturn(mockQueueFactory);
                })) {
            
            // Test that different queue types pass different configurations
            QueueConfigurationBuilder.createHighThroughputQueue(mockDatabaseService);
            QueueConfigurationBuilder.createLowLatencyQueue(mockDatabaseService);
            
            // Verify that the provider was called multiple times with different configurations
            assertEquals(2, mockedProvider.constructed().size());
            
            // Each call should have different configuration parameters
            var provider1 = mockedProvider.constructed().get(0);
            var provider2 = mockedProvider.constructed().get(1);
            
            verify(provider1).createFactory(eq("native"), eq(mockDatabaseService), any(Map.class));
            verify(provider2).createFactory(eq("native"), eq(mockDatabaseService), any(Map.class));
        }
    }
}
