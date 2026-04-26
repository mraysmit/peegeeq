package dev.mars.peegeeq.servicemanager.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConsulServiceDiscovery using real Consul via Testcontainers.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsulServiceDiscoveryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsulServiceDiscoveryIntegrationTest.class);

    @SuppressWarnings("resource")
    @Container
    static ConsulContainer consul = new ConsulContainer("hashicorp/consul:1.15.3")
            .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).forStatusCode(200));

    private ConsulServiceDiscovery serviceDiscovery;
    private ConsulClient consulClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        ConsulClientOptions consulOptions = new ConsulClientOptions()
                .setHost(consul.getHost())
                .setPort(consul.getFirstMappedPort());

        consulClient = ConsulClient.create(vertx, consulOptions);
        serviceDiscovery = new ConsulServiceDiscovery(vertx, consulClient, objectMapper);

        logger.info("ConsulServiceDiscovery integration test setup completed ({}:{})",
                consul.getHost(), consul.getFirstMappedPort());
        testContext.completeNow();
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<String> testInstanceIds = instances.stream()
                            .map(PeeGeeQInstance::getInstanceId)
                            .filter(id -> id.startsWith("test-"))
                            .toList();

                    if (testInstanceIds.isEmpty()) {
                        return Future.succeededFuture();
                    }

                    logger.info("Cleaning up {} test instances", testInstanceIds.size());
                    return Future.all(testInstanceIds.stream()
                            .map(serviceDiscovery::deregisterInstance)
                            .toList()).mapEmpty();
                })
                .onComplete(result -> {
                    if (result.failed()) {
                        logger.warn("Test cleanup encountered issues", result.cause());
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
                .onFailure(testContext::failNow);
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
                .compose(v -> vertx.timer(1000).compose(timerId -> serviceDiscovery.discoverInstances()))
                .onSuccess(instances -> testContext.verify(() -> {
                    assertNotNull(instances);
                    assertFalse(instances.isEmpty());

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
                .onFailure(testContext::failNow);
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
                .compose(v -> vertx.timer(1000).compose(timerId ->
                        serviceDiscovery.getInstance("test-specific-01")))
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
                .onFailure(testContext::failNow);
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
                .compose(v -> serviceDiscovery.deregisterInstance("test-deregister-01"))
                .compose(v -> vertx.timer(1000).compose(timerId -> serviceDiscovery.discoverInstances()))
                .onSuccess(instances -> testContext.verify(() -> {
                    boolean instanceFound = instances.stream()
                            .anyMatch(inst -> "test-deregister-01".equals(inst.getInstanceId()));

                    assertFalse(instanceFound, "Deregistered instance should not be discovered");

                    logger.info("Successfully verified instance deregistration");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
                .compose(v -> vertx.timer(1000).compose(timerId ->
                        serviceDiscovery.checkInstanceHealth("test-health-01")))
                .onSuccess(health -> testContext.verify(() -> {
                    assertNotNull(health);
                    assertTrue(
                            health == ServiceHealth.HEALTHY || health == ServiceHealth.UNHEALTHY || health == ServiceHealth.UNKNOWN,
                            "Health must be a known state");

                    logger.info("Health check result for test instance: {}", health);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
                .compose(v -> vertx.timer(2000).compose(timerId -> serviceDiscovery.discoverInstances()))
                .onSuccess(discoveredInstances -> testContext.verify(() -> {
                    List<PeeGeeQInstance> cachedInstances = serviceDiscovery.getCachedInstances();

                    assertNotNull(cachedInstances);

                    boolean cache01Found = cachedInstances.stream()
                            .anyMatch(inst -> "test-cache-01".equals(inst.getInstanceId()));
                    boolean cache02Found = cachedInstances.stream()
                            .anyMatch(inst -> "test-cache-02".equals(inst.getInstanceId()));

                    assertTrue(cache01Found, "test-cache-01 should be in cache");
                    assertTrue(cache02Found, "test-cache-02 should be in cache");

                    logger.info("Successfully verified cached instances: {} total", cachedInstances.size());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
