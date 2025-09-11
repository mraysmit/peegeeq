package dev.mars.peegeeq.servicemanager;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for PeeGeeQ Service Manager.
 *
 * Note: These tests require Consul to be running on localhost:8500
 * DISABLED: Use PeeGeeQServiceManagerIntegrationTest instead (uses Testcontainers)
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
@Disabled("Use PeeGeeQServiceManagerIntegrationTest instead - requires Consul running")
class PeeGeeQServiceManagerTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQServiceManagerTest.class);
    
    private static final int TEST_PORT = 9091;
    private WebClient webClient;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        webClient = WebClient.create(vertx);

        // Deploy the service manager using Vert.x 5.x Future pattern
        PeeGeeQServiceManager serviceManager = new PeeGeeQServiceManager(TEST_PORT);

        vertx.deployVerticle(serviceManager)
            .onSuccess(id -> {
                logger.info("Service Manager deployed with ID: {}", id);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        testContext.completeNow();
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
                    assertEquals("peegeeq-service-manager", health.getString("service"));
                    assertNotNull(health.getString("timestamp"));

                    logger.info("Health check response: {}", health.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testInstanceRegistration(Vertx vertx, VertxTestContext testContext) {
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-instance-01")
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
                    assertEquals("test-instance-01", result.getString("instanceId"));
                    assertEquals("registered", result.getString("status"));

                    logger.info("Registration response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testListInstances(Vertx vertx, VertxTestContext testContext) {
        // First register an instance
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-instance-02")
                .put("host", "localhost")
                .put("port", 8081)
                .put("version", "1.0.0")
                .put("environment", "test");
        
        // Use Vert.x 5.x compose pattern for chained operations
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .compose(registerResponse -> {
                    assertEquals(201, registerResponse.statusCode());

                    // Then list instances
                    return webClient.get(TEST_PORT, "localhost", "/api/v1/instances")
                            .send();
                })
                .onSuccess(listResponse -> testContext.verify(() -> {
                    assertEquals(200, listResponse.statusCode());

                    JsonObject result = listResponse.bodyAsJsonObject();
                    assertNotNull(result);
                    assertEquals("Instances retrieved successfully", result.getString("message"));
                    assertTrue(result.getInteger("instanceCount") >= 0);
                    assertNotNull(result.getJsonArray("instances"));

                    logger.info("List instances response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
                    assertTrue(result.containsKey("aggregatedData"));
                    assertTrue(result.containsKey("timestamp"));

                    logger.info("Federated overview response: {}", result.encodePrettily());
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
                    assertTrue(result.containsKey("timestamp"));

                    logger.info("Federated queues response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testInvalidInstanceRegistration(Vertx vertx, VertxTestContext testContext) {
        JsonObject invalidData = new JsonObject()
                .put("instanceId", "") // Invalid empty ID
                .put("host", "localhost")
                .put("port", 8080);

        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(invalidData)
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());

                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("error"));
                    assertEquals("instanceId is required", result.getString("error"));

                    logger.info("Invalid registration response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testInstanceDeregistration(Vertx vertx, VertxTestContext testContext) {
        // First register an instance
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-deregister-01")
                .put("host", "localhost")
                .put("port", 8090)
                .put("version", "1.0.0")
                .put("environment", "test");

        // Use Vert.x 5.x compose pattern for register then deregister
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .compose(registerResponse -> {
                    assertEquals(201, registerResponse.statusCode());

                    // Then deregister it
                    return webClient.delete(TEST_PORT, "localhost", "/api/v1/instances/test-deregister-01/deregister")
                            .send();
                })
                .onSuccess(deregisterResponse -> testContext.verify(() -> {
                    assertEquals(200, deregisterResponse.statusCode());

                    JsonObject result = deregisterResponse.bodyAsJsonObject();
                    assertNotNull(result);
                    assertEquals("Instance deregistered successfully", result.getString("message"));
                    assertEquals("test-deregister-01", result.getString("instanceId"));
                    assertEquals("deregistered", result.getString("status"));

                    logger.info("Deregistration response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testInstanceHealthCheck(Vertx vertx, VertxTestContext testContext) {
        // Register an instance first
        JsonObject registrationData = new JsonObject()
                .put("instanceId", "test-health-01")
                .put("host", "localhost")
                .put("port", 8091)
                .put("version", "1.0.0")
                .put("environment", "test");

        // Use Vert.x 5.x compose pattern with timer
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(registrationData)
                .compose(registerResponse -> {
                    assertEquals(201, registerResponse.statusCode());

                    // Wait a moment for registration to propagate using Future timer
                    return vertx.timer(1000)
                            .compose(timerId -> webClient.get(TEST_PORT, "localhost", "/api/v1/instances/test-health-01/health")
                                    .send());
                })
                .onSuccess(healthResponse -> testContext.verify(() -> {
                    assertEquals(200, healthResponse.statusCode());

                    JsonObject result = healthResponse.bodyAsJsonObject();
                    assertNotNull(result);
                    assertEquals("test-health-01", result.getString("instanceId"));
                    assertTrue(result.containsKey("status"));
                    assertTrue(result.containsKey("healthy"));
                    assertTrue(result.containsKey("timestamp"));

                    logger.info("Health check response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testListInstancesWithFilters(Vertx vertx, VertxTestContext testContext) {
        // Register instances in different environments
        JsonObject prodInstance = new JsonObject()
                .put("instanceId", "test-filter-prod-01")
                .put("host", "localhost")
                .put("port", 8092)
                .put("environment", "production")
                .put("region", "us-east-1");

        JsonObject testInstance = new JsonObject()
                .put("instanceId", "test-filter-test-01")
                .put("host", "localhost")
                .put("port", 8093)
                .put("environment", "test")
                .put("region", "us-west-1");

        // Use Vert.x 5.x compose pattern for complex chained operations
        webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(prodInstance)
                .compose(prodResponse -> {
                    assertEquals(201, prodResponse.statusCode());

                    return webClient.post(TEST_PORT, "localhost", "/api/v1/instances/register")
                            .putHeader("Content-Type", "application/json")
                            .sendJsonObject(testInstance);
                })
                .compose(testResponse -> {
                    assertEquals(201, testResponse.statusCode());

                    // Wait for registrations to propagate using Future timer
                    return vertx.timer(1000)
                            .compose(timerId -> webClient.get(TEST_PORT, "localhost", "/api/v1/instances?environment=production")
                                    .send());
                })
                .onSuccess(listResponse -> testContext.verify(() -> {
                    assertEquals(200, listResponse.statusCode());

                    JsonObject result = listResponse.bodyAsJsonObject();
                    assertNotNull(result);
                    assertTrue(result.containsKey("filteredByEnvironment"));
                    assertEquals("production", result.getString("filteredByEnvironment"));

                    logger.info("Filtered instances response: {}", result.encodePrettily());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
