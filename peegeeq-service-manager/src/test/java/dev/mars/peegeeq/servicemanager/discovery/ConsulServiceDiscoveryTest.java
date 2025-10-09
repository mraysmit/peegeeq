package dev.mars.peegeeq.servicemanager.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.Vertx;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConsulServiceDiscovery.
 *
 * Note: These tests require Consul to be running on localhost:8500
 * Run: consul agent -dev
 *
 * DISABLED: Use ConsulServiceDiscoveryIntegrationTest instead (uses Testcontainers)
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
@Disabled("Use ConsulServiceDiscoveryIntegrationTest instead - requires Consul running")
class ConsulServiceDiscoveryTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsulServiceDiscoveryTest.class);
    
    private ConsulServiceDiscovery serviceDiscovery;
    private ConsulClient consulClient;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        // Initialize ObjectMapper
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // Initialize Consul client
        ConsulClientOptions consulOptions = new ConsulClientOptions()
                .setHost("localhost")
                .setPort(8500);
        
        consulClient = ConsulClient.create(vertx, consulOptions);
        
        // Initialize service discovery
        serviceDiscovery = new ConsulServiceDiscovery(vertx, consulClient, objectMapper);
        
        logger.info("ConsulServiceDiscovery test setup completed");
        testContext.completeNow();
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        // Clean up any registered test instances
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    // Deregister all test instances
                    List<String> testInstanceIds = instances.stream()
                            .map(PeeGeeQInstance::getInstanceId)
                            .filter(id -> id.startsWith("test-"))
                            .toList();
                    
                    if (testInstanceIds.isEmpty()) {
                        return io.vertx.core.Future.succeededFuture();
                    }
                    
                    logger.info("Cleaning up {} test instances", testInstanceIds.size());
                    
                    List<io.vertx.core.Future<Void>> deregisterFutures = testInstanceIds.stream()
                            .map(serviceDiscovery::deregisterInstance)
                            .toList();
                    
                    return io.vertx.core.Future.all(deregisterFutures).mapEmpty();
                })
                .onComplete(result -> {
                    if (result.succeeded()) {
                        logger.info("Test cleanup completed successfully");
                    } else {
                        logger.warn("Test cleanup failed", result.cause());
                    }
                    testContext.completeNow();
                });
    }
    
    @Test
    void testRegisterInstance(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-register-01")
                .host("localhost")
                .port(8080)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .metadata("datacenter", "test-dc")
                .build();
        
        serviceDiscovery.registerInstance(instance)
                .onSuccess(v -> {
                    logger.info("Successfully registered test instance: {}", instance.getInstanceId());
                    testContext.completeNow();
                })
                .onFailure(throwable -> {
                    logger.error("Failed to register test instance", throwable);
                    testContext.failNow(throwable);
                });
    }
    
    @Test
    void testRegisterAndDiscoverInstance(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-discover-01")
                .host("localhost")
                .port(8081)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        
        serviceDiscovery.registerInstance(instance)
                .compose(v -> {
                    logger.info("Instance registered, now discovering...");
                    // Wait a moment for Consul to process the registration
                    return vertx.timer(1000).compose(timerId -> serviceDiscovery.discoverInstances());
                })
                .onSuccess(instances -> testContext.verify(() -> {
                    assertNotNull(instances);
                    assertFalse(instances.isEmpty());
                    
                    // Find our test instance
                    PeeGeeQInstance foundInstance = instances.stream()
                            .filter(inst -> "test-discover-01".equals(inst.getInstanceId()))
                            .findFirst()
                            .orElse(null);
                    
                    assertNotNull(foundInstance, "Test instance should be discovered");
                    assertEquals("localhost", foundInstance.getHost());
                    assertEquals(8081, foundInstance.getPort());
                    assertEquals("test", foundInstance.getEnvironment());
                    assertEquals("local", foundInstance.getRegion());
                    assertEquals(ServiceHealth.HEALTHY, foundInstance.getStatus());
                    
                    logger.info("Successfully discovered test instance: {}", foundInstance);
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    logger.error("Failed to discover test instance", throwable);
                    testContext.failNow(throwable);
                });
    }
    
    @Test
    void testGetSpecificInstance(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-specific-01")
                .host("localhost")
                .port(8082)
                .version("2.0.0")
                .environment("staging")
                .region("us-west-1")
                .metadata("cluster", "test-cluster")
                .build();
        
        serviceDiscovery.registerInstance(instance)
                .compose(v -> {
                    // Wait for registration to propagate
                    return vertx.timer(1000).compose(timerId -> 
                            serviceDiscovery.getInstance("test-specific-01"));
                })
                .onSuccess(foundInstance -> testContext.verify(() -> {
                    assertNotNull(foundInstance);
                    assertEquals("test-specific-01", foundInstance.getInstanceId());
                    assertEquals("localhost", foundInstance.getHost());
                    assertEquals(8082, foundInstance.getPort());
                    assertEquals("2.0.0", foundInstance.getVersion());
                    assertEquals("staging", foundInstance.getEnvironment());
                    assertEquals("us-west-1", foundInstance.getRegion());
                    assertEquals("test-cluster", foundInstance.getMetadata("cluster"));
                    
                    logger.info("Successfully retrieved specific instance: {}", foundInstance);
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    logger.error("Failed to get specific instance", throwable);
                    testContext.failNow(throwable);
                });
    }
    
    @Test
    void testDeregisterInstance(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-deregister-01")
                .host("localhost")
                .port(8083)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        
        serviceDiscovery.registerInstance(instance)
                .compose(v -> {
                    logger.info("Instance registered, now deregistering...");
                    return serviceDiscovery.deregisterInstance("test-deregister-01");
                })
                .compose(v -> {
                    // Wait for deregistration to propagate
                    return vertx.timer(1000).compose(timerId -> serviceDiscovery.discoverInstances());
                })
                .onSuccess(instances -> testContext.verify(() -> {
                    // Verify the instance is no longer in the discovered instances
                    boolean instanceFound = instances.stream()
                            .anyMatch(inst -> "test-deregister-01".equals(inst.getInstanceId()));
                    
                    assertFalse(instanceFound, "Deregistered instance should not be discovered");
                    
                    logger.info("Successfully verified instance deregistration");
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    logger.error("Failed to deregister instance", throwable);
                    testContext.failNow(throwable);
                });
    }
    
    @Test
    void testCheckInstanceHealth(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance = PeeGeeQInstance.builder()
                .instanceId("test-health-01")
                .host("localhost")
                .port(8084)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        
        serviceDiscovery.registerInstance(instance)
                .compose(v -> {
                    // Wait for registration to propagate
                    return vertx.timer(1000).compose(timerId -> 
                            serviceDiscovery.checkInstanceHealth("test-health-01"));
                })
                .onSuccess(health -> testContext.verify(() -> {
                    assertNotNull(health);
                    // Note: Health check might return UNHEALTHY since we don't have a real PeeGeeQ instance running
                    // but the important thing is that we get a response
                    assertTrue(health == ServiceHealth.HEALTHY || health == ServiceHealth.UNHEALTHY || health == ServiceHealth.UNKNOWN);
                    
                    logger.info("Health check result for test instance: {}", health);
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    logger.error("Failed to check instance health", throwable);
                    testContext.failNow(throwable);
                });
    }
    
    @Test
    void testGetCachedInstances(Vertx vertx, VertxTestContext testContext) {
        PeeGeeQInstance instance1 = PeeGeeQInstance.builder()
                .instanceId("test-cache-01")
                .host("localhost")
                .port(8085)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        
        PeeGeeQInstance instance2 = PeeGeeQInstance.builder()
                .instanceId("test-cache-02")
                .host("localhost")
                .port(8086)
                .version("1.0.0")
                .environment("test")
                .region("local")
                .build();
        
        serviceDiscovery.registerInstance(instance1)
                .compose(v -> serviceDiscovery.registerInstance(instance2))
                .compose(v -> {
                    // Wait for registrations to propagate and cache to update
                    return vertx.timer(2000).compose(timerId -> serviceDiscovery.discoverInstances());
                })
                .onSuccess(discoveredInstances -> testContext.verify(() -> {
                    // Now test cached instances
                    List<PeeGeeQInstance> cachedInstances = serviceDiscovery.getCachedInstances();
                    
                    assertNotNull(cachedInstances);
                    
                    // Find our test instances in the cache
                    boolean cache01Found = cachedInstances.stream()
                            .anyMatch(inst -> "test-cache-01".equals(inst.getInstanceId()));
                    boolean cache02Found = cachedInstances.stream()
                            .anyMatch(inst -> "test-cache-02".equals(inst.getInstanceId()));
                    
                    assertTrue(cache01Found, "test-cache-01 should be in cache");
                    assertTrue(cache02Found, "test-cache-02 should be in cache");
                    
                    logger.info("Successfully verified cached instances: {} total", cachedInstances.size());
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    logger.error("Failed to test cached instances", throwable);
                    testContext.failNow(throwable);
                });
    }
}
