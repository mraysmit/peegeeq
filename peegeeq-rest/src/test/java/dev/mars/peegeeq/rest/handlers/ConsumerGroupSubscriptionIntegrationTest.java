package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Consumer Group + Subscription Options + SSE workflow.
 * 
 * Tests the complete REST API workflow from creating consumer groups through
 * setting subscription options to connecting via SSE with those options applied.
 * Also tests validation and error cases.
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end consumer group + SSE workflow
 */
@Tag("integration")
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsumerGroupSubscriptionIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupSubscriptionIntegrationTest.class);
    private static final int TEST_PORT = 18081;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_consumer_group_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);
    
    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private HttpClient httpClient;
    private WebClient webClient;
    private static final String QUEUE_NAME = "test_consumer_group_queue";
    
    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Consumer Group + Subscription Integration Test ===");
        
        // Start REST server
        server = new PeeGeeQRestServer(TEST_PORT);
        vertx.deployVerticle(server)
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                
                // Create HTTP client and WebClient
                httpClient = vertx.createHttpClient();
                webClient = WebClient.create(vertx);
                
                // Give server time to fully start
                vertx.setTimer(1000, timerId -> {
                    // Create database setup with queue
                    setupId = "consumer_group_test_" + System.currentTimeMillis();
                    
                    JsonObject setupRequest = new JsonObject()
                        .put("setupId", setupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "consumer_group_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", "public")
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray()
                            .add(new JsonObject()
                                .put("queueName", QUEUE_NAME)
                                .put("maxRetries", 3)
                                .put("visibilityTimeout", 30)))
                        .put("eventStores", new JsonArray())
                        .put("additionalProperties", new JsonObject().put("test_type", "consumer_group"));
                    
                    logger.info("Creating database setup via REST API: {}", setupId);
                    
                    webClient.post(TEST_PORT, "localhost", "/api/v1/setups")
                        .sendJsonObject(setupRequest)
                        .onSuccess(response -> {
                            if (response.statusCode() == 200 || response.statusCode() == 201) {
                                logger.info("Database setup created: {}", setupId);
                                testContext.completeNow();
                            } else {
                                logger.error("Failed to create setup: {} - {}", 
                                          response.statusCode(), response.bodyAsString());
                                testContext.failNow(new Exception("Failed to create setup: " + response.statusCode()));
                            }
                        })
                        .onFailure(testContext::failNow);
                });
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @AfterAll
    void teardown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Consumer Group + Subscription Integration Test ===");
        
        if (webClient != null) {
            webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/" + setupId)
                .send()
                .onComplete(ar -> {
                    if (deploymentId != null) {
                        vertx.undeploy(deploymentId)
                            .onComplete(result -> testContext.completeNow());
                    } else {
                        testContext.completeNow();
                    }
                });
        } else {
            testContext.completeNow();
        }
        
        try {
            testContext.awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Test
    @Order(1)
    void testSetSubscriptionOptionsWithoutConsumerGroup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 1: Set Subscription Options Without Creating Consumer Group ===");
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60);
        
        String path = String.format("/api/v1/consumer-groups/%s/%s/nonexistent-group/subscription",
                                    setupId, QUEUE_NAME);
        
        webClient.post(TEST_PORT, "localhost", path)
            .sendJsonObject(subscriptionOptions)
            .onSuccess(response -> {
                logger.info("Response status: {}", response.statusCode());
                logger.info("Response body: {}", response.bodyAsString());
                
                // Should return 404 - consumer group not found
                assertEquals(404, response.statusCode(), 
                           "Should return 404 when consumer group doesn't exist");
                
                JsonObject errorResponse = response.bodyAsJsonObject();
                assertNotNull(errorResponse.getString("error"));
                assertTrue(errorResponse.getString("error").contains("not found"));
                
                logger.info("✅ Correctly rejected subscription options for non-existent group");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(2)
    void testSSEWithNonExistentConsumerGroup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: SSE Connection With Non-Existent Consumer Group ===");
        
        String path = String.format("/api/v1/queues/%s/%s/stream?consumerGroup=nonexistent-group",
                                    setupId, QUEUE_NAME);
        
        httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> {
                logger.info("SSE Response status: {}", response.statusCode());
                
                // Should succeed with 200 but use default options
                assertEquals(200, response.statusCode(), 
                           "SSE should succeed even with non-existent consumer group");
                
                // Read SSE events
                StringBuilder sseData = new StringBuilder();
                response.handler(buffer -> sseData.append(buffer.toString()));
                
                // Wait for initial events
                vertx.setTimer(2000, id -> {
                    String events = sseData.toString();
                    logger.info("SSE Events received:\n{}", events);
                    
                    // Should receive connection event
                    assertTrue(events.contains("event: connection"));
                    
                    // Should receive configured event showing defaults
                    assertTrue(events.contains("event: configured"));
                    assertTrue(events.contains("\"startPosition\":\"FROM_NOW\""));
                    
                    logger.info("✅ SSE gracefully handled non-existent consumer group");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(3)
    void testCompleteWorkflow(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Test 3: Complete Consumer Group + Subscription Options + SSE Workflow ===");
        
        String groupName = "test-consumer-group";
        
        // Step 1: Create consumer group
        logger.info("Step 1: Creating consumer group '{}'", groupName);
        JsonObject createGroupRequest = new JsonObject()
            .put("groupName", groupName)
            .put("maxMembers", 5);
        
        String createGroupPath = String.format("/api/v1/queues/%s/%s/consumer-groups",
                                              setupId, QUEUE_NAME);
        
        webClient.post(TEST_PORT, "localhost", createGroupPath)
            .sendJsonObject(createGroupRequest)
            .compose(createResponse -> {
                logger.info("Create group response: {}", createResponse.statusCode());
                
                if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
                    logger.error("Failed to create consumer group: {}", createResponse.bodyAsString());
                    testContext.failNow(new Exception("Failed to create consumer group"));
                    return null;
                }
                
                logger.info("✅ Consumer group created successfully");
                
                // Step 2: Set subscription options
                logger.info("Step 2: Setting subscription options for '{}'", groupName);
                JsonObject subscriptionOptions = new JsonObject()
                    .put("startPosition", "FROM_BEGINNING")
                    .put("heartbeatIntervalSeconds", 45);
                
                String setOptionsPath = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription",
                                                     setupId, QUEUE_NAME, groupName);
                
                return webClient.post(TEST_PORT, "localhost", setOptionsPath)
                    .sendJsonObject(subscriptionOptions);
            })
            .compose(optionsResponse -> {
                logger.info("Set options response: {} - {}", 
                          optionsResponse.statusCode(), optionsResponse.bodyAsString());
                
                assertEquals(200, optionsResponse.statusCode(),
                           "Should successfully set subscription options");
                
                JsonObject response = optionsResponse.bodyAsJsonObject();
                assertEquals("FROM_BEGINNING", 
                           response.getJsonObject("subscriptionOptions")
                                  .getString("startPosition"));
                assertEquals(45, 
                           response.getJsonObject("subscriptionOptions")
                                  .getInteger("heartbeatIntervalSeconds"));
                
                logger.info("✅ Subscription options set successfully");
                
                // Step 3: Connect via SSE with consumer group
                logger.info("Step 3: Connecting via SSE with consumer group '{}'", groupName);
                String ssePath = String.format("/api/v1/queues/%s/%s/stream?consumerGroup=%s",
                                              setupId, QUEUE_NAME, groupName);
                
                return httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", ssePath)
                    .compose(request -> {
                        request.putHeader("Accept", "text/event-stream");
                        return request.send();
                    });
            })
            .onSuccess(sseResponse -> {
                logger.info("SSE Response status: {}", sseResponse.statusCode());
                assertEquals(200, sseResponse.statusCode(), "SSE should connect successfully");
                
                // Read SSE events
                StringBuilder sseData = new StringBuilder();
                sseResponse.handler(buffer -> sseData.append(buffer.toString()));
                
                // Wait for initial events
                vertx.setTimer(2000, id -> {
                    String events = sseData.toString();
                    logger.info("SSE Events received:\n{}", events);
                    
                    // Parse events
                    try {
                        JsonObject connectionEvent = extractEventData(events, "connection");
                        JsonObject configuredEvent = extractEventData(events, "configured");
                        
                        // Verify connection event includes consumer group
                        assertNotNull(connectionEvent, "Should receive connection event");
                        assertEquals(groupName, connectionEvent.getString("consumerGroup"),
                                   "Connection event should include consumer group name");
                        
                        // Verify configured event uses subscription options
                        assertNotNull(configuredEvent, "Should receive configured event");
                        assertEquals("FROM_BEGINNING", configuredEvent.getString("startPosition"),
                                   "Should use FROM_BEGINNING from subscription options");
                        assertEquals(45, configuredEvent.getInteger("heartbeatIntervalSeconds"),
                                   "Should use custom heartbeat interval from subscription options");
                        assertEquals(groupName, configuredEvent.getString("consumerGroup"),
                                   "Configured event should include consumer group name");
                        
                        logger.info("✅ Complete workflow successful:");
                        logger.info("   - Consumer group created");
                        logger.info("   - Subscription options configured");
                        logger.info("   - SSE connected with subscription options applied");
                        
                        testContext.completeNow();
                        
                    } catch (Exception e) {
                        logger.error("Failed to parse SSE events", e);
                        testContext.failNow(e);
                    }
                });
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @Test
    @Order(4)
    void testGetSubscriptionOptionsForNonExistentGroup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: Get Subscription Options For Non-Existent Group ===");
        
        String path = String.format("/api/v1/consumer-groups/%s/%s/nonexistent-group/subscription",
                                    setupId, QUEUE_NAME);
        
        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                logger.info("Response status: {}", response.statusCode());
                logger.info("Response body: {}", response.bodyAsString());
                
                // Should return 200 with default options (current behavior)
                assertEquals(200, response.statusCode());
                
                JsonObject responseObj = response.bodyAsJsonObject();
                JsonObject options = responseObj.getJsonObject("subscriptionOptions");
                
                // Should return defaults
                assertEquals("FROM_NOW", options.getString("startPosition"));
                assertNotNull(options.getInteger("heartbeatIntervalSeconds"));
                
                logger.info("✅ Returns default options for non-existent group");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(5)
    void testDeleteSubscriptionOptions(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: Delete Subscription Options ===");
        
        String groupName = "test-delete-group";
        
        // Step 1: Create consumer group
        JsonObject createGroupRequest = new JsonObject()
            .put("groupName", groupName)
            .put("maxMembers", 5);
        
        String createGroupPath = String.format("/api/v1/queues/%s/%s/consumer-groups",
                                              setupId, QUEUE_NAME);
        
        webClient.post(TEST_PORT, "localhost", createGroupPath)
            .sendJsonObject(createGroupRequest)
            .compose(createResponse -> {
                logger.info("Consumer group created");
                
                // Step 2: Set subscription options
                JsonObject subscriptionOptions = new JsonObject()
                    .put("startPosition", "FROM_BEGINNING");
                
                String setOptionsPath = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription",
                                                     setupId, QUEUE_NAME, groupName);
                
                return webClient.post(TEST_PORT, "localhost", setOptionsPath)
                    .sendJsonObject(subscriptionOptions);
            })
            .compose(optionsResponse -> {
                logger.info("Subscription options set");
                
                // Step 3: Delete subscription options
                String deletePath = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription",
                                                 setupId, QUEUE_NAME, groupName);
                
                return webClient.delete(TEST_PORT, "localhost", deletePath)
                    .send();
            })
            .onSuccess(deleteResponse -> {
                logger.info("Delete response status: {}", deleteResponse.statusCode());
                
                assertEquals(204, deleteResponse.statusCode(),
                           "Should return 204 No Content on successful delete");
                
                logger.info("✅ Subscription options deleted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(6)
    void testSSEWithoutConsumerGroupUsesDefaults(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: SSE Without Consumer Group Uses Defaults ===");
        
        // Connect without consumerGroup parameter
        String path = String.format("/api/v1/queues/%s/%s/stream", setupId, QUEUE_NAME);
        
        httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> {
                logger.info("SSE Response status: {}", response.statusCode());
                assertEquals(200, response.statusCode());
                
                StringBuilder sseData = new StringBuilder();
                response.handler(buffer -> sseData.append(buffer.toString()));
                
                vertx.setTimer(2000, id -> {
                    String events = sseData.toString();
                    logger.info("SSE Events:\n{}", events);
                    
                    try {
                        JsonObject connectionEvent = extractEventData(events, "connection");
                        JsonObject configuredEvent = extractEventData(events, "configured");
                        
                        // Verify null consumer group
                        assertNull(connectionEvent.getValue("consumerGroup"),
                                 "Consumer group should be null when not specified");
                        
                        // Verify default subscription options
                        assertEquals("FROM_NOW", configuredEvent.getString("startPosition"),
                                   "Should use default FROM_NOW");
                        assertEquals(60, configuredEvent.getInteger("heartbeatIntervalSeconds"),
                                   "Should use default 60s heartbeat");
                        
                        logger.info("✅ SSE without consumer group uses defaults correctly");
                        testContext.completeNow();
                        
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            })
            .onFailure(testContext::failNow);
    }
    
    /**
     * Helper method to extract event data from SSE stream.
     */
    private JsonObject extractEventData(String sseEvents, String eventType) throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(sseEvents));
        String line;
        boolean inTargetEvent = false;
        StringBuilder dataBuilder = new StringBuilder();
        
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: " + eventType)) {
                inTargetEvent = true;
            } else if (inTargetEvent && line.startsWith("data: ")) {
                dataBuilder.append(line.substring(6)); // Remove "data: " prefix
            } else if (inTargetEvent && line.isEmpty()) {
                // End of event
                break;
            }
        }
        
        String data = dataBuilder.toString();
        if (data.isEmpty()) {
            return null;
        }
        
        return new JsonObject(data);
    }
}
