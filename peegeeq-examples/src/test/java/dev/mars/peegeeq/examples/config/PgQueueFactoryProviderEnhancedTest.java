package dev.mars.peegeeq.examples.config;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
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
        // Register native factory for testing (no mocking)
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
    }

    @Test
    void testEnhancedConfigurationSchema() {
        // Test that the enhanced configuration schema includes all expected properties
        // Skip if no real factory implementations are available (expected in peegeeq-db module)

        var supportedTypes = provider.getSupportedTypes();
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - skipping schema test (expected in peegeeq-db module)");
            return;
        }

        String testFactoryType = supportedTypes.iterator().next();
        logger.info("Testing configuration schema with factory type: {}", testFactoryType);

        Map<String, Object> schema = provider.getConfigurationSchema(testFactoryType);
        assertNotNull(schema);
        assertTrue(schema.containsKey("type"));
        assertTrue(schema.containsKey("description"));
        assertTrue(schema.containsKey("properties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);

        // Check common properties that all factory types should have
        assertTrue(properties.containsKey("batch-size"));
        assertTrue(properties.containsKey("polling-interval"));
        assertTrue(properties.containsKey("max-retries"));
        assertTrue(properties.containsKey("visibility-timeout"));
        assertTrue(properties.containsKey("dead-letter-enabled"));
        assertTrue(properties.containsKey("prefetch-count"));
        assertTrue(properties.containsKey("concurrent-consumers"));
        assertTrue(properties.containsKey("buffer-size"));

        logger.info("{} queue schema contains {} properties", testFactoryType, properties.size());

        // Test that we can get schemas for any registered factory types
        for (String factoryType : provider.getSupportedTypes()) {
            Map<String, Object> factorySchema = provider.getConfigurationSchema(factoryType);
            assertNotNull(factorySchema, "Schema should not be null for type: " + factoryType);
            assertTrue(factorySchema.containsKey("type"), "Schema should have 'type' for: " + factoryType);
            assertTrue(factorySchema.containsKey("properties"), "Schema should have 'properties' for: " + factoryType);
            logger.info("Factory type '{}' schema contains {} properties",
                factoryType, ((Map<?, ?>) factorySchema.get("properties")).size());
        }
    }
    
    @Test
    void testConfigurationSchemaStructure() {
        // Test that configuration schema has proper structure
        var supportedTypes = provider.getSupportedTypes();
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - skipping schema structure test (expected in peegeeq-db module)");
            return;
        }

        String testFactoryType = supportedTypes.iterator().next();
        Map<String, Object> schema = provider.getConfigurationSchema(testFactoryType);

        // Should have top-level structure
        assertEquals("object", schema.get("type"));
        assertTrue(schema.get("description").toString().contains(testFactoryType));

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
        // Test that provider supports real factory implementations (no mocking)
        var supportedTypes = provider.getSupportedTypes();

        // In peegeeq-db module, no implementations may be available - this is expected
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - this is expected in peegeeq-db module");
            // Test that the provider correctly reports no types
            assertFalse(provider.isTypeSupported("native"));
            assertFalse(provider.isTypeSupported("outbox"));
            return;
        }

        // Test that native and outbox may or may not be available (depends on classpath)
        // This is expected behavior since factories register themselves
        logger.info("Native factory available: {}", provider.isTypeSupported("native"));
        logger.info("Outbox factory available: {}", provider.isTypeSupported("outbox"));

        // Test case insensitivity for available types
        for (String type : supportedTypes) {
            assertTrue(provider.isTypeSupported(type.toUpperCase()));
            assertTrue(provider.isTypeSupported(type.toLowerCase()));
        }

        // Test invalid types
        assertFalse(provider.isTypeSupported("invalid"));
        assertFalse(provider.isTypeSupported(""));
        assertFalse(provider.isTypeSupported(null));

        logger.info("Supported implementation types tests passed");
    }
    
    @Test
    void testDefaultImplementationType() {
        // Test that provider handles default implementation type correctly
        var supportedTypes = provider.getSupportedTypes();

        if (supportedTypes.isEmpty()) {
            // When no implementations are available, should throw appropriate exception
            logger.info("No factory implementations available - testing error handling");
            assertThrows(IllegalStateException.class, () -> provider.getDefaultType(),
                "Should throw IllegalStateException when no implementations are available");
            return;
        }

        // When implementations are available, should return a valid default
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
        var supportedTypes = provider.getSupportedTypes();
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - skipping property types test (expected in peegeeq-db module)");
            return;
        }

        String testFactoryType = supportedTypes.iterator().next();
        Map<String, Object> schema = provider.getConfigurationSchema(testFactoryType);

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
        var supportedTypes = provider.getSupportedTypes();
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - skipping schema documentation test (expected in peegeeq-db module)");
            return;
        }

        String testFactoryType = supportedTypes.iterator().next();
        Map<String, Object> schema = provider.getConfigurationSchema(testFactoryType);

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

        var supportedTypes = provider.getSupportedTypes();
        if (supportedTypes.isEmpty()) {
            logger.info("No factory implementations available - skipping implementation-specific properties test (expected in peegeeq-db module)");
            return;
        }

        for (String factoryType : supportedTypes) {
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
