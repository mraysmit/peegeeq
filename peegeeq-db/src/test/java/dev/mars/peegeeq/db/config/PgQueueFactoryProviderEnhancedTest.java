package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
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
    }
    
    @Test
    void testEnhancedConfigurationSchema() {
        // Test that the enhanced configuration schema includes all expected properties
        
        // Test native queue schema
        Map<String, Object> nativeSchema = provider.getConfigurationSchema("native");
        assertNotNull(nativeSchema);
        assertTrue(nativeSchema.containsKey("type"));
        assertTrue(nativeSchema.containsKey("description"));
        assertTrue(nativeSchema.containsKey("properties"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> nativeProperties = (Map<String, Object>) nativeSchema.get("properties");
        assertNotNull(nativeProperties);
        
        // Check common properties
        assertTrue(nativeProperties.containsKey("batch-size"));
        assertTrue(nativeProperties.containsKey("polling-interval"));
        assertTrue(nativeProperties.containsKey("max-retries"));
        assertTrue(nativeProperties.containsKey("visibility-timeout"));
        assertTrue(nativeProperties.containsKey("dead-letter-enabled"));
        assertTrue(nativeProperties.containsKey("prefetch-count"));
        assertTrue(nativeProperties.containsKey("concurrent-consumers"));
        assertTrue(nativeProperties.containsKey("buffer-size"));
        
        // Check native-specific properties
        assertTrue(nativeProperties.containsKey("listen-notify-enabled"));
        assertTrue(nativeProperties.containsKey("connection-pool-size"));
        
        logger.info("Native queue schema contains {} properties", nativeProperties.size());
        
        // Test outbox queue schema
        Map<String, Object> outboxSchema = provider.getConfigurationSchema("outbox");
        assertNotNull(outboxSchema);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> outboxProperties = (Map<String, Object>) outboxSchema.get("properties");
        assertNotNull(outboxProperties);
        
        // Check outbox-specific properties
        assertTrue(outboxProperties.containsKey("retention-period"));
        assertTrue(outboxProperties.containsKey("cleanup-interval"));
        
        logger.info("Outbox queue schema contains {} properties", outboxProperties.size());
    }
    
    @Test
    void testConfigurationSchemaStructure() {
        // Test that configuration schema has proper structure
        Map<String, Object> schema = provider.getConfigurationSchema("native");
        
        // Should have top-level structure
        assertEquals("object", schema.get("type"));
        assertTrue(schema.get("description").toString().contains("native"));
        
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
        // Test that provider supports expected implementation types
        assertTrue(provider.isTypeSupported("native"));
        assertTrue(provider.isTypeSupported("outbox"));
        assertTrue(provider.isTypeSupported("NATIVE")); // Case insensitive
        assertTrue(provider.isTypeSupported("OUTBOX")); // Case insensitive
        
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
        Map<String, Object> schema = provider.getConfigurationSchema("native");
        
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
        Map<String, Object> schema = provider.getConfigurationSchema("native");
        
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
        // Test that different implementation types have different specific properties
        Map<String, Object> nativeSchema = provider.getConfigurationSchema("native");
        Map<String, Object> outboxSchema = provider.getConfigurationSchema("outbox");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> nativeProperties = (Map<String, Object>) nativeSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> outboxProperties = (Map<String, Object>) outboxSchema.get("properties");
        
        // Native should have LISTEN/NOTIFY properties
        assertTrue(nativeProperties.containsKey("listen-notify-enabled"));
        assertTrue(nativeProperties.containsKey("connection-pool-size"));
        
        // Outbox should have retention properties
        assertTrue(outboxProperties.containsKey("retention-period"));
        assertTrue(outboxProperties.containsKey("cleanup-interval"));
        
        // Outbox should not have LISTEN/NOTIFY properties
        assertFalse(outboxProperties.containsKey("listen-notify-enabled"));
        assertFalse(outboxProperties.containsKey("connection-pool-size"));
        
        // Native should not have retention properties
        assertFalse(nativeProperties.containsKey("retention-period"));
        assertFalse(nativeProperties.containsKey("cleanup-interval"));
        
        logger.info("Implementation-specific properties tests passed");
    }
}
