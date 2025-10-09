package dev.mars.peegeeq.servicemanager;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PeeGeeQServiceManager using real Consul via Testcontainers.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
class PeeGeeQServiceManagerIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQServiceManagerIntegrationTest.class);
    private static final int TEST_PORT = 9090;
    
    @Container
    static ConsulContainer consul = new ConsulContainer("hashicorp/consul:1.15.3");
    
    private PeeGeeQServiceManager serviceManager;
    private WebClient webClient;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        // Configure system properties for Consul connection
        System.setProperty("consul.host", consul.getHost());
        System.setProperty("consul.port", String.valueOf(consul.getFirstMappedPort()));
        
        // Create and deploy service manager using Vert.x 5.x Future pattern
        serviceManager = new PeeGeeQServiceManager();

        vertx.deployVerticle(serviceManager)
            .onSuccess(deploymentId -> {
                logger.info("Service Manager deployed successfully");

                // Create web client for testing
                webClient = WebClient.create(vertx);
                testContext.completeNow();
            })
            .onFailure(throwable -> {
                logger.error("Failed to deploy Service Manager", throwable);
                testContext.failNow(throwable);
            });
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        
        if (serviceManager != null) {
            vertx.undeploy(serviceManager.deploymentID())
                .onSuccess(v -> {
                    logger.info("Service Manager undeployed successfully");
                    testContext.completeNow();
                })
                .onFailure(throwable -> {
                    logger.warn("Failed to undeploy Service Manager", throwable);
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
    }
    
    @Test
    void testHealthEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/health")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject health = response.bodyAsJsonObject();
                    assertNotNull(health);
                    assertEquals("UP", health.getString("status"));
                    assertTrue(health.containsKey("timestamp"));

                    logger.info("Health endpoint test passed: {}", health.encode());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testInstanceRegistration(Vertx vertx, VertxTestContext testContext) {
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-instance-integration-01")
                .put("host", "localhost")
                .put("port", 8080)
                .put("version", "1.0.0")
                .put("environment", "test")
                .put("region", "local")
                .put("metadata", new JsonObject()
                        .put("datacenter", "test-dc")
                        .put("cluster", "test-cluster"));
        
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .onSuccess(response -> testContext.verify(() -> {
                    if (response.statusCode() != 201) {
                        logger.error("Registration failed with status {}: {}", response.statusCode(), response.bodyAsString());
                    }
                    assertEquals(201, response.statusCode());

                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertEquals("Instance registered successfully", result.getString("message"));
                    assertEquals("test-instance-integration-01", result.getString("instanceId"));

                    logger.info("Instance registration test passed: {}", result.encode());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testInstanceDiscovery(Vertx vertx, VertxTestContext testContext) {
        // First register an instance
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-instance-discovery-01")
                .put("host", "localhost")
                .put("port", 8081)
                .put("version", "1.0.0")
                .put("environment", "test")
                .put("region", "local");
        
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .compose(registerResponse -> {
                    assertEquals(201, registerResponse.statusCode());
                    
                    // Now discover instances
                    return webClient.get(TEST_PORT, "localhost", "/api/v1/instances")
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("instances"));
                    assertTrue(result.getJsonArray("instances").size() > 0);
                    
                    logger.info("Instance discovery test passed: found {} instances", 
                            result.getJsonArray("instances").size());
                    testContext.completeNow();
                })));
    }
    
    @Test
    void testInstanceUnregistration(Vertx vertx, VertxTestContext testContext) {
        String instanceId = "test-instance-unregister-01";
        
        // First register an instance
        JsonObject registrationData = new JsonObject()
                .put("instanceId", instanceId)
                .put("host", "localhost")
                .put("port", 8082)
                .put("version", "1.0.0")
                .put("environment", "test")
                .put("region", "local");
        
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .compose(registerResponse -> {
                    assertEquals(201, registerResponse.statusCode());
                    
                    // Now unregister the instance
                    return webClient.delete(TEST_PORT, "localhost", "/api/v1/instances/" + instanceId + "/deregister")
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertEquals("Instance unregistered successfully", result.getString("message"));
                    assertEquals(instanceId, result.getString("instanceId"));
                    
                    logger.info("Instance unregistration test passed: {}", result.encode());
                    testContext.completeNow();
                })));
    }
    
    @Test
    void testFederatedOverview(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/federated/overview")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("message"));
                    assertTrue(result.containsKey("instanceCount"));
                    assertTrue(result.containsKey("timestamp"));

                    logger.info("Federated overview test passed: {}", result.encode());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testFederatedQueues(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/federated/queues")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("message"));
                    assertTrue(result.containsKey("instanceCount"));
                    assertTrue(result.containsKey("queueCount"));
                    assertTrue(result.containsKey("queues"));

                    logger.info("Federated queues test passed: found {} queues across {} instances",
                            result.getInteger("queueCount"), result.getInteger("instanceCount"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testFederatedConsumerGroups(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/federated/consumer-groups")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("message"));
                    assertTrue(result.containsKey("instanceCount"));
                    assertTrue(result.containsKey("groupCount"));
                    assertTrue(result.containsKey("consumerGroups"));

                    logger.info("Federated consumer groups test passed: found {} groups across {} instances",
                            result.getInteger("groupCount"), result.getInteger("instanceCount"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
