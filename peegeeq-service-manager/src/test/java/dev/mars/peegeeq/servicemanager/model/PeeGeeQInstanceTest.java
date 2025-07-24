package dev.mars.peegeeq.servicemanager.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PeeGeeQInstance model class.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
class PeeGeeQInstanceTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQInstanceTest.class);
    
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Test
    void testInstanceCreationWithBuilder() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("datacenter", "dc1");
        metadata.put("cluster", "main");
        
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-01")
                .host("localhost")
                .port(8080)
                .version("1.0.0")
                .environment("production")
                .region("us-east-1")
                .metadata(metadata)
                .build();
        
        assertEquals("test-instance-01", instance.getInstanceId());
        assertEquals("localhost", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals("1.0.0", instance.getVersion());
        assertEquals("production", instance.getEnvironment());
        assertEquals("us-east-1", instance.getRegion());
        assertEquals("dc1", instance.getMetadata("datacenter"));
        assertEquals("main", instance.getMetadata("cluster"));
        assertEquals(ServiceHealth.UNKNOWN, instance.getStatus()); // Default status
        
        logger.info("Instance creation with builder test passed: {}", instance);
    }
    
    @Test
    void testInstanceCreationWithConstructor() {
        PeeGeeQInstance instance = new PeeGeeQInstance(
                "test-instance-02",
                "192.168.1.100",
                8081,
                "2.0.0",
                "staging",
                "eu-west-1"
        );
        
        assertEquals("test-instance-02", instance.getInstanceId());
        assertEquals("192.168.1.100", instance.getHost());
        assertEquals(8081, instance.getPort());
        assertEquals("2.0.0", instance.getVersion());
        assertEquals("staging", instance.getEnvironment());
        assertEquals("eu-west-1", instance.getRegion());
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
        
        logger.info("Instance creation with constructor test passed: {}", instance);
    }
    
    @Test
    void testDefaultValues() {
        PeeGeeQInstance instance = new PeeGeeQInstance(
                "test-instance-03",
                "localhost",
                8082,
                null, // version
                null, // environment
                null  // region
        );
        
        assertEquals("unknown", instance.getVersion());
        assertEquals("default", instance.getEnvironment());
        assertEquals("default", instance.getRegion());
        
        logger.info("Default values test passed: version={}, env={}, region={}", 
                instance.getVersion(), instance.getEnvironment(), instance.getRegion());
    }
    
    @Test
    void testUrlGeneration() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-04")
                .host("api.example.com")
                .port(443)
                .build();
        
        assertEquals("http://api.example.com:443", instance.getBaseUrl());
        assertEquals("http://api.example.com:443/api/v1/management", instance.getManagementUrl());
        assertEquals("http://api.example.com:443/health", instance.getHealthUrl());
        
        logger.info("URL generation test passed: base={}, management={}, health={}", 
                instance.getBaseUrl(), instance.getManagementUrl(), instance.getHealthUrl());
    }
    
    @Test
    void testHealthStatus() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-05")
                .host("localhost")
                .port(8083)
                .build();
        
        // Default status should be UNKNOWN
        assertEquals(ServiceHealth.UNKNOWN, instance.getStatus());
        assertFalse(instance.isHealthy());
        
        // Set to healthy
        instance.setStatus(ServiceHealth.HEALTHY);
        assertEquals(ServiceHealth.HEALTHY, instance.getStatus());
        assertTrue(instance.isHealthy());
        
        // Set to unhealthy
        instance.setStatus(ServiceHealth.UNHEALTHY);
        assertEquals(ServiceHealth.UNHEALTHY, instance.getStatus());
        assertFalse(instance.isHealthy());
        
        logger.info("Health status test passed");
    }
    
    @Test
    void testEnvironmentAndRegionChecks() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-06")
                .host("localhost")
                .port(8084)
                .environment("production")
                .region("us-west-2")
                .build();
        
        assertTrue(instance.isInEnvironment("production"));
        assertFalse(instance.isInEnvironment("staging"));
        assertFalse(instance.isInEnvironment("development"));
        
        assertTrue(instance.isInRegion("us-west-2"));
        assertFalse(instance.isInRegion("us-east-1"));
        assertFalse(instance.isInRegion("eu-west-1"));
        
        logger.info("Environment and region checks test passed");
    }
    
    @Test
    void testMetadataManagement() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-07")
                .host("localhost")
                .port(8085)
                .build();
        
        // Initially empty metadata
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
        assertNull(instance.getMetadata("nonexistent"));
        
        // Add metadata
        instance.addMetadata("key1", "value1");
        instance.addMetadata("key2", "value2");
        
        assertEquals("value1", instance.getMetadata("key1"));
        assertEquals("value2", instance.getMetadata("key2"));
        assertEquals(2, instance.getMetadata().size());
        
        // Set metadata map
        Map<String, String> newMetadata = new HashMap<>();
        newMetadata.put("key3", "value3");
        newMetadata.put("key4", "value4");
        instance.setMetadata(newMetadata);
        
        assertEquals("value3", instance.getMetadata("key3"));
        assertEquals("value4", instance.getMetadata("key4"));
        assertNull(instance.getMetadata("key1")); // Should be replaced
        assertEquals(2, instance.getMetadata().size());
        
        logger.info("Metadata management test passed");
    }
    
    @Test
    void testTimestamps() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-08")
                .host("localhost")
                .port(8086)
                .build();
        
        // Initially null timestamps
        assertNull(instance.getRegisteredAt());
        assertNull(instance.getLastHealthCheck());
        
        // Set timestamps
        Instant now = Instant.now();
        instance.setRegisteredAt(now);
        instance.setLastHealthCheck(now);
        
        assertEquals(now, instance.getRegisteredAt());
        assertEquals(now, instance.getLastHealthCheck());
        
        logger.info("Timestamps test passed");
    }
    
    @Test
    void testEqualsAndHashCode() {
        PeeGeeQInstance instance1 = PeeGeeQInstance.builder()
                .instanceId("test-instance-09")
                .host("localhost")
                .port(8087)
                .build();
        
        PeeGeeQInstance instance2 = PeeGeeQInstance.builder()
                .instanceId("test-instance-09") // Same ID
                .host("different-host")
                .port(9999)
                .build();
        
        PeeGeeQInstance instance3 = PeeGeeQInstance.builder()
                .instanceId("test-instance-10") // Different ID
                .host("localhost")
                .port(8087)
                .build();
        
        // Instances with same ID should be equal
        assertEquals(instance1, instance2);
        assertEquals(instance1.hashCode(), instance2.hashCode());
        
        // Instances with different IDs should not be equal
        assertNotEquals(instance1, instance3);
        assertNotEquals(instance1.hashCode(), instance3.hashCode());
        
        logger.info("Equals and hashCode test passed");
    }
    
    @Test
    void testToString() {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-10")
                .host("localhost")
                .port(8088)
                .version("1.5.0")
                .environment("test")
                .region("local")
                .build();
        
        instance.setStatus(ServiceHealth.HEALTHY);
        instance.setRegisteredAt(Instant.now());
        
        String toString = instance.toString();

        logger.info("Actual toString output: {}", toString);

        assertNotNull(toString);
        assertTrue(toString.contains("test-instance-10"));
        assertTrue(toString.contains("localhost"));
        assertTrue(toString.contains("8088"));
        assertTrue(toString.contains("1.5.0"));
        assertTrue(toString.contains("test"));
        assertTrue(toString.contains("local"));
        assertTrue(toString.contains("healthy"));

        logger.info("ToString test passed: {}", toString);
    }
    
    @Test
    void testJsonSerialization() throws Exception {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-instance-11")
                .host("localhost")
                .port(8089)
                .version("1.0.0")
                .environment("production")
                .region("us-east-1")
                .metadata("datacenter", "dc1")
                .metadata("cluster", "main")
                .build();
        
        instance.setStatus(ServiceHealth.HEALTHY);
        instance.setRegisteredAt(Instant.now());
        
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(instance);
        assertNotNull(json);
        assertTrue(json.contains("test-instance-11"));
        
        // Deserialize from JSON
        PeeGeeQInstance deserialized = objectMapper.readValue(json, PeeGeeQInstance.class);
        
        assertEquals(instance.getInstanceId(), deserialized.getInstanceId());
        assertEquals(instance.getHost(), deserialized.getHost());
        assertEquals(instance.getPort(), deserialized.getPort());
        assertEquals(instance.getVersion(), deserialized.getVersion());
        assertEquals(instance.getEnvironment(), deserialized.getEnvironment());
        assertEquals(instance.getRegion(), deserialized.getRegion());
        
        logger.info("JSON serialization test passed");
    }
}
