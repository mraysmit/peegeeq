package dev.mars.peegeeq.servicemanager.routing;

import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LoadBalancer functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class LoadBalancerTest {
    
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerTest.class);
    
    private List<PeeGeeQInstance> testInstances;
    private LoadBalancer loadBalancer;
    
    @BeforeEach
    void setUp() {
        // Create test instances
        testInstances = new ArrayList<>();
        
        // Healthy instances
        PeeGeeQInstance instance1 = PeeGeeQInstance.builder()
                .instanceId("test-lb-01")
                .host("localhost")
                .port(8080)
                .version("1.0.0")
                .environment("test")
                .region("us-east-1")
                .build();
        instance1.setStatus(ServiceHealth.HEALTHY);
        testInstances.add(instance1);
        
        PeeGeeQInstance instance2 = PeeGeeQInstance.builder()
                .instanceId("test-lb-02")
                .host("localhost")
                .port(8081)
                .version("1.0.0")
                .environment("test")
                .region("us-east-1")
                .build();
        instance2.setStatus(ServiceHealth.HEALTHY);
        testInstances.add(instance2);
        
        PeeGeeQInstance instance3 = PeeGeeQInstance.builder()
                .instanceId("test-lb-03")
                .host("localhost")
                .port(8082)
                .version("1.0.0")
                .environment("production")
                .region("us-west-1")
                .build();
        instance3.setStatus(ServiceHealth.HEALTHY);
        testInstances.add(instance3);
        
        // Unhealthy instance
        PeeGeeQInstance instance4 = PeeGeeQInstance.builder()
                .instanceId("test-lb-04")
                .host("localhost")
                .port(8083)
                .version("1.0.0")
                .environment("test")
                .region("us-east-1")
                .build();
        instance4.setStatus(ServiceHealth.UNHEALTHY);
        testInstances.add(instance4);
        
        // Initialize load balancer
        loadBalancer = new LoadBalancer(LoadBalancingStrategy.ROUND_ROBIN);
    }
    
    @Test
    void testRoundRobinSelection() {
        loadBalancer.resetCounter(); // Ensure clean state
        
        // Test round-robin selection
        PeeGeeQInstance selected1 = loadBalancer.selectInstance(testInstances);
        PeeGeeQInstance selected2 = loadBalancer.selectInstance(testInstances);
        PeeGeeQInstance selected3 = loadBalancer.selectInstance(testInstances);
        PeeGeeQInstance selected4 = loadBalancer.selectInstance(testInstances); // Should wrap around
        
        assertNotNull(selected1);
        assertNotNull(selected2);
        assertNotNull(selected3);
        assertNotNull(selected4);
        
        // All selected instances should be healthy
        assertTrue(selected1.isHealthy());
        assertTrue(selected2.isHealthy());
        assertTrue(selected3.isHealthy());
        assertTrue(selected4.isHealthy());
        
        // Should not select the unhealthy instance
        assertNotEquals("test-lb-04", selected1.getInstanceId());
        assertNotEquals("test-lb-04", selected2.getInstanceId());
        assertNotEquals("test-lb-04", selected3.getInstanceId());
        assertNotEquals("test-lb-04", selected4.getInstanceId());
        
        // Should cycle through healthy instances
        assertNotEquals(selected1.getInstanceId(), selected2.getInstanceId());
        assertNotEquals(selected2.getInstanceId(), selected3.getInstanceId());
        
        // Fourth selection should wrap around to first
        assertEquals(selected1.getInstanceId(), selected4.getInstanceId());
        
        logger.info("Round-robin selection test passed: {} -> {} -> {} -> {}", 
                selected1.getInstanceId(), selected2.getInstanceId(), 
                selected3.getInstanceId(), selected4.getInstanceId());
    }
    
    @Test
    void testRandomSelection() {
        LoadBalancer randomBalancer = new LoadBalancer(LoadBalancingStrategy.RANDOM);
        
        // Test multiple random selections
        List<String> selectedIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PeeGeeQInstance selected = randomBalancer.selectInstance(testInstances);
            assertNotNull(selected);
            assertTrue(selected.isHealthy());
            assertNotEquals("test-lb-04", selected.getInstanceId()); // Should not select unhealthy
            selectedIds.add(selected.getInstanceId());
        }
        
        // Should have some variation in selections (not all the same)
        long uniqueSelections = selectedIds.stream().distinct().count();
        assertTrue(uniqueSelections > 1, "Random selection should produce some variation");
        
        logger.info("Random selection test passed with {} unique selections out of 10", uniqueSelections);
    }
    
    @Test
    void testFirstAvailableSelection() {
        LoadBalancer firstAvailableBalancer = new LoadBalancer(LoadBalancingStrategy.FIRST_AVAILABLE);
        
        // Test multiple first-available selections
        PeeGeeQInstance selected1 = firstAvailableBalancer.selectInstance(testInstances);
        PeeGeeQInstance selected2 = firstAvailableBalancer.selectInstance(testInstances);
        PeeGeeQInstance selected3 = firstAvailableBalancer.selectInstance(testInstances);
        
        assertNotNull(selected1);
        assertNotNull(selected2);
        assertNotNull(selected3);
        
        // All selections should be the same (first healthy instance)
        assertEquals(selected1.getInstanceId(), selected2.getInstanceId());
        assertEquals(selected2.getInstanceId(), selected3.getInstanceId());
        
        // Should be healthy and not the unhealthy instance
        assertTrue(selected1.isHealthy());
        assertNotEquals("test-lb-04", selected1.getInstanceId());
        
        logger.info("First available selection test passed: consistently selected {}", selected1.getInstanceId());
    }
    
    @Test
    void testEnvironmentFiltering() {
        // Test selection with environment filter
        PeeGeeQInstance selected = loadBalancer.selectInstance(testInstances, "test");
        
        assertNotNull(selected);
        assertTrue(selected.isHealthy());
        assertEquals("test", selected.getEnvironment());
        
        // Should not select production instance
        assertNotEquals("test-lb-03", selected.getInstanceId());
        
        logger.info("Environment filtering test passed: selected {} from test environment", selected.getInstanceId());
    }
    
    @Test
    void testEnvironmentAndRegionFiltering() {
        // Test selection with both environment and region filters
        PeeGeeQInstance selected = loadBalancer.selectInstance(testInstances, "test", "us-east-1");
        
        assertNotNull(selected);
        assertTrue(selected.isHealthy());
        assertEquals("test", selected.getEnvironment());
        assertEquals("us-east-1", selected.getRegion());
        
        // Should not select production or us-west-1 instances
        assertNotEquals("test-lb-03", selected.getInstanceId());
        
        logger.info("Environment and region filtering test passed: selected {} from test/us-east-1", selected.getInstanceId());
    }
    
    @Test
    void testNoHealthyInstances() {
        // Create list with only unhealthy instances
        List<PeeGeeQInstance> unhealthyInstances = new ArrayList<>();
        PeeGeeQInstance unhealthyInstance = PeeGeeQInstance.builder()
                .instanceId("unhealthy-01")
                .host("localhost")
                .port(8090)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        unhealthyInstance.setStatus(ServiceHealth.UNHEALTHY);
        unhealthyInstances.add(unhealthyInstance);
        
        PeeGeeQInstance selected = loadBalancer.selectInstance(unhealthyInstances);
        
        assertNull(selected, "Should return null when no healthy instances available");
        
        logger.info("No healthy instances test passed: correctly returned null");
    }
    
    @Test
    void testEmptyInstanceList() {
        List<PeeGeeQInstance> emptyList = new ArrayList<>();
        
        PeeGeeQInstance selected = loadBalancer.selectInstance(emptyList);
        
        assertNull(selected, "Should return null for empty instance list");
        
        logger.info("Empty instance list test passed: correctly returned null");
    }
    
    @Test
    void testGetHealthyInstances() {
        List<PeeGeeQInstance> healthyInstances = loadBalancer.getHealthyInstances(testInstances);
        
        assertNotNull(healthyInstances);
        assertEquals(3, healthyInstances.size()); // 3 healthy out of 4 total
        
        // All returned instances should be healthy
        for (PeeGeeQInstance instance : healthyInstances) {
            assertTrue(instance.isHealthy());
        }
        
        logger.info("Get healthy instances test passed: found {} healthy instances", healthyInstances.size());
    }
    
    @Test
    void testGetHealthyInstancesWithFilters() {
        // Test with environment filter
        List<PeeGeeQInstance> testEnvInstances = loadBalancer.getHealthyInstances(testInstances, "test");
        assertEquals(2, testEnvInstances.size()); // 2 healthy test instances
        
        // Test with environment and region filters
        List<PeeGeeQInstance> filteredInstances = loadBalancer.getHealthyInstances(testInstances, "test", "us-east-1");
        assertEquals(2, filteredInstances.size()); // 2 healthy test instances in us-east-1
        
        logger.info("Filtered healthy instances test passed: test env={}, test+region={}", 
                testEnvInstances.size(), filteredInstances.size());
    }
    
    @Test
    void testHasHealthyInstances() {
        assertTrue(loadBalancer.hasHealthyInstances(testInstances));
        
        // Test with only unhealthy instances
        List<PeeGeeQInstance> unhealthyOnly = testInstances.stream()
                .filter(instance -> !instance.isHealthy())
                .toList();
        
        assertFalse(loadBalancer.hasHealthyInstances(unhealthyOnly));
        
        logger.info("Has healthy instances test passed");
    }
    
    @Test
    void testGetHealthyInstanceCount() {
        int count = loadBalancer.getHealthyInstanceCount(testInstances);
        assertEquals(3, count); // 3 healthy out of 4 total
        
        logger.info("Get healthy instance count test passed: {}", count);
    }
    
    @Test
    void testLoadBalancingStrategies() {
        // Test strategy creation methods
        LoadBalancer roundRobin = LoadBalancer.roundRobin();
        assertEquals(LoadBalancingStrategy.ROUND_ROBIN, roundRobin.getStrategy());
        
        LoadBalancer random = LoadBalancer.random();
        assertEquals(LoadBalancingStrategy.RANDOM, random.getStrategy());
        
        LoadBalancer firstAvailable = LoadBalancer.firstAvailable();
        assertEquals(LoadBalancingStrategy.FIRST_AVAILABLE, firstAvailable.getStrategy());
        
        LoadBalancer custom = LoadBalancer.create(LoadBalancingStrategy.RANDOM);
        assertEquals(LoadBalancingStrategy.RANDOM, custom.getStrategy());
        
        logger.info("Load balancing strategies test passed");
    }
}
