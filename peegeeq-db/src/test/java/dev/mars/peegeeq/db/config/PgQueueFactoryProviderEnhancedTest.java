package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.test.TestFactoryRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test for enhanced PgQueueFactoryProvider functionality.
 * Tests the enhanced configuration schema and named configuration support.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class PgQueueFactoryProviderEnhancedTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PgQueueFactoryProviderEnhancedTest.class);
    
    private PgQueueFactoryProvider provider;
    
    @BeforeEach
    void setUp() {
        provider = new PgQueueFactoryProvider();
        // Register available factories for testing
        TestFactoryRegistration.registerAvailableFactories(provider);
    }
    
    @Test
    void testEnhancedConfigurationSchema() {
        // Test that the enhanced configuration schema includes all expected properties

        // Test mock queue schema (always available in tests)
        Map<String, Object> mockSchema = provider.getConfigurationSchema("mock");
        assertNotNull(mockSchema);
        assertTrue(mockSchema.containsKey("type"));
        assertTrue(mockSchema.containsKey("description"));
        assertTrue(mockSchema.containsKey("properties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mockProperties = (Map<String, Object>) mockSchema.get("properties");
        assertNotNull(mockProperties);

        // Check common properties
        assertTrue(mockProperties.containsKey("batch-size"));
        assertTrue(mockProperties.containsKey("polling-interval"));
        assertTrue(mockProperties.containsKey("max-retries"));
        assertTrue(mockProperties.containsKey("visibility-timeout"));
        assertTrue(mockProperties.containsKey("dead-letter-enabled"));
        assertTrue(mockProperties.containsKey("prefetch-count"));
        assertTrue(mockProperties.containsKey("concurrent-consumers"));
        assertTrue(mockProperties.containsKey("buffer-size"));

        logger.info("Mock queue schema contains {} properties", mockProperties.size());

        // Test that we can get schemas for any registered factory types
        for (String factoryType : provider.getSupportedTypes()) {
            Map<String, Object> schema = provider.getConfigurationSchema(factoryType);
            assertNotNull(schema, "Schema should not be null for type: " + factoryType);
            assertTrue(schema.containsKey("type"), "Schema should have 'type' for: " + factoryType);
            assertTrue(schema.containsKey("properties"), "Schema should have 'properties' for: " + factoryType);
            logger.info("Factory type '{}' schema contains {} properties",
                factoryType, ((Map<?, ?>) schema.get("properties")).size());
        }
    }
    
    @Test
    void testConfigurationSchemaStructure() {
        // Test that configuration schema has proper structure
        Map<String, Object> schema = provider.getConfigurationSchema("mock");

        // Should have top-level structure
        assertEquals("object", schema.get("type"));
        assertTrue(schema.get("description").toString().contains("mock"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        
        // Each property should have proper structure
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> propertyDef = (Map<String, Object>) entry.getValue();
            
            assertTrue(propertyDef.containsKey("type"), 
                "Property " + propertyName + " should have type");
            assertTrue(propertyDef.containsKey("default"), 
                "Property " + propertyName + " should have default value");
            assertTrue(propertyDef.containsKey("description"), 
                "Property " + propertyName + " should have description");
        }
        
        logger.info("Configuration schema structure tests passed");
    }
    
    @Test
    void testNamedConfigurationSupport() {
        // Test that named configuration methods exist
        Class<?> providerClass = provider.getClass();
        
        // Check for createNamedFactory method
        boolean hasCreateNamedFactory = false;
        boolean hasLoadNamedConfiguration = false;
        
        for (Method method : providerClass.getDeclaredMethods()) {
            switch (method.getName()) {
                case "createNamedFactory" -> {
                    hasCreateNamedFactory = true;
                    assertEquals(4, method.getParameterCount());
                    // Should have parameters: String, String, DatabaseService, Map
                }
                case "loadNamedConfiguration" -> {
                    hasLoadNamedConfiguration = true;
                    assertEquals(1, method.getParameterCount());
                    assertEquals(String.class, method.getParameterTypes()[0]);
                }
            }
        }
        
        assertTrue(hasCreateNamedFactory, "Should have createNamedFactory method");
        assertTrue(hasLoadNamedConfiguration, "Should have loadNamedConfiguration method");
        
        logger.info("Named configuration support tests passed");
    }
    
    @Test
    void testSupportedImplementationTypes() {
        // Test that provider supports mock factory (always available in tests)
        assertTrue(provider.isTypeSupported("mock"));
        assertTrue(provider.isTypeSupported("MOCK")); // Case insensitive

        // Test that native and outbox may or may not be available (depends on classpath)
        // This is now expected behavior since factories register themselves
        logger.info("Native factory available: {}", provider.isTypeSupported("native"));
        logger.info("Outbox factory available: {}", provider.isTypeSupported("outbox"));

        // Test invalid types
        assertFalse(provider.isTypeSupported("invalid"));
        assertFalse(provider.isTypeSupported(""));
        assertFalse(provider.isTypeSupported(null));

        logger.info("Supported implementation types tests passed");
    }
    
    @Test
    void testDefaultImplementationType() {
        // Test that provider has a default implementation type
        String defaultType = provider.getDefaultType();
        assertNotNull(defaultType);
        assertFalse(defaultType.isEmpty());
        assertTrue(provider.isTypeSupported(defaultType));
        
        logger.info("Default implementation type: {}", defaultType);
    }
    
    @Test
    void testConfigurationSchemaForUnsupportedType() {
        // Test that unsupported types throw appropriate exceptions
        assertThrows(IllegalArgumentException.class, () -> {
            provider.getConfigurationSchema("unsupported");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            provider.getConfigurationSchema("");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            provider.getConfigurationSchema(null);
        });
        
        logger.info("Unsupported type handling tests passed");
    }
    
    @Test
    void testProviderInterfaceCompliance() {
        // Test that PgQueueFactoryProvider implements QueueFactoryProvider correctly
        assertTrue(QueueFactoryProvider.class.isAssignableFrom(PgQueueFactoryProvider.class));
        
        // Test that all interface methods are implemented
        Method[] interfaceMethods = QueueFactoryProvider.class.getMethods();
        for (Method interfaceMethod : interfaceMethods) {
            if (!interfaceMethod.isDefault()) {
                assertDoesNotThrow(() -> {
                    provider.getClass().getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
                }, "Method " + interfaceMethod.getName() + " should be implemented");
            }
        }
        
        logger.info("Provider interface compliance tests passed");
    }
    
    @Test
    void testConfigurationPropertyTypes() {
        // Test that configuration properties have correct types
        Map<String, Object> schema = provider.getConfigurationSchema("mock");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        
        // Test specific property types
        @SuppressWarnings("unchecked")
        Map<String, Object> batchSizeProperty = (Map<String, Object>) properties.get("batch-size");
        assertEquals("integer", batchSizeProperty.get("type"));
        assertTrue(batchSizeProperty.get("default") instanceof Integer);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> pollingIntervalProperty = (Map<String, Object>) properties.get("polling-interval");
        assertEquals("string", pollingIntervalProperty.get("type"));
        assertTrue(pollingIntervalProperty.get("default") instanceof String);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> deadLetterProperty = (Map<String, Object>) properties.get("dead-letter-enabled");
        assertEquals("boolean", deadLetterProperty.get("type"));
        assertTrue(deadLetterProperty.get("default") instanceof Boolean);
        
        logger.info("Configuration property types tests passed");
    }
    
    @Test
    void testSchemaDocumentation() {
        // Test that schema includes proper documentation
        Map<String, Object> schema = provider.getConfigurationSchema("mock");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> propertyDef = (Map<String, Object>) entry.getValue();
            
            String description = (String) propertyDef.get("description");
            assertNotNull(description, "Property " + propertyName + " should have description");
            assertFalse(description.isEmpty(), "Property " + propertyName + " description should not be empty");
            assertTrue(description.length() > 10, "Property " + propertyName + " description should be meaningful");
        }
        
        logger.info("Schema documentation tests passed");
    }
    
    @Test
    void testImplementationSpecificProperties() {
        // Test that implementation types can have their own configuration schemas
        // This test verifies that the schema system works for any registered factory type

        for (String factoryType : provider.getSupportedTypes()) {
            Map<String, Object> schema = provider.getConfigurationSchema(factoryType);
            assertNotNull(schema, "Schema should exist for factory type: " + factoryType);

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertNotNull(properties, "Properties should exist for factory type: " + factoryType);

            // All factory types should have common properties
            assertTrue(properties.containsKey("batch-size"),
                "Factory type " + factoryType + " should have batch-size property");
            assertTrue(properties.containsKey("polling-interval"),
                "Factory type " + factoryType + " should have polling-interval property");

            logger.info("Factory type '{}' has {} properties", factoryType, properties.size());
        }

        logger.info("Implementation-specific properties tests passed");
    }
}
