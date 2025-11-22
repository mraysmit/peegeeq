package dev.mars.peegeeq.rest.handlers;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Integration tests for Consumer Group Subscription Options (Phase 3.2).
 * 
 * Tests the new subscription options endpoints for consumer groups including:
 * - Start position control (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
 * - Heartbeat configuration
 * - CRUD operations on subscription options
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SubscriptionOptionsIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionOptionsIntegrationTest.class);
    
    private Vertx vertx;
    private WebClient client;
    private static final int PORT = 18085;
    
    @BeforeAll
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Subscription Options Integration Test ===");
        
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);
        
        // Setup will be created in individual tests as needed
        testContext.completeNow();
    }
    
    @AfterAll
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("=== Tearing down Subscription Options Integration Test ===");
        
        if (client != null) {
            client.close();
        }
        if (vertx != null) {
            vertx.close().onComplete(testContext.succeedingThenComplete());
        }
    }
    
    @Test
    @DisplayName("Test 1: Update subscription options with FROM_NOW position")
    void testUpdateSubscriptionOptions_FromNow() throws Exception {
        logger.info("=== Test 1: Update subscription options with FROM_NOW ===");
        
        String setupId = "test-setup-1";
        String queueName = "test-queue";
        String groupName = "test-group-1";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_NOW")
            .put("heartbeatIntervalSeconds", 30)
            .put("heartbeatTimeoutSeconds", 180);
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                Assertions.assertTrue(body.containsKey("subscriptionOptions"));
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_NOW", options.getString("startPosition"));
                Assertions.assertEquals(30, options.getInteger("heartbeatIntervalSeconds"));
                Assertions.assertEquals(180, options.getInteger("heartbeatTimeoutSeconds"));
                
                logger.info("✅ Subscription options updated successfully with FROM_NOW");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 2: Update subscription options with FROM_BEGINNING position")
    void testUpdateSubscriptionOptions_FromBeginning() throws Exception {
        logger.info("=== Test 2: Update subscription options with FROM_BEGINNING ===");
        
        String setupId = "test-setup-2";
        String queueName = "test-queue";
        String groupName = "test-group-2";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_BEGINNING", options.getString("startPosition"));
                
                logger.info("✅ Subscription options updated successfully with FROM_BEGINNING");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 3: Update subscription options with FROM_MESSAGE_ID position")
    void testUpdateSubscriptionOptions_FromMessageId() throws Exception {
        logger.info("=== Test 3: Update subscription options with FROM_MESSAGE_ID ===");
        
        String setupId = "test-setup-3";
        String queueName = "test-queue";
        String groupName = "test-group-3";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_MESSAGE_ID")
            .put("startFromMessageId", 12345L)
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                Assertions.assertEquals(12345L, options.getLong("startFromMessageId"));
                
                logger.info("✅ Subscription options updated successfully with FROM_MESSAGE_ID");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 4: Update subscription options with FROM_TIMESTAMP position")
    void testUpdateSubscriptionOptions_FromTimestamp() throws Exception {
        logger.info("=== Test 4: Update subscription options with FROM_TIMESTAMP ===");
        
        String setupId = "test-setup-4";
        String queueName = "test-queue";
        String groupName = "test-group-4";
        
        String timestamp = "2025-11-22T00:00:00Z";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_TIMESTAMP")
            .put("startFromTimestamp", timestamp)
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_TIMESTAMP", options.getString("startPosition"));
                Assertions.assertNotNull(options.getString("startFromTimestamp"));
                
                logger.info("✅ Subscription options updated successfully with FROM_TIMESTAMP");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 5: Get subscription options (returns defaults if none configured)")
    void testGetSubscriptionOptions_Defaults() throws Exception {
        logger.info("=== Test 5: Get subscription options - defaults ===");
        
        String setupId = "test-setup-5";
        String queueName = "test-queue";
        String groupName = "test-group-5";
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.get(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                Assertions.assertTrue(body.containsKey("subscriptionOptions"));
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_NOW", options.getString("startPosition")); // Default
                Assertions.assertEquals(60, options.getInteger("heartbeatIntervalSeconds")); // Default
                Assertions.assertEquals(300, options.getInteger("heartbeatTimeoutSeconds")); // Default
                
                logger.info("✅ Default subscription options returned correctly");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 6: Get subscription options after update")
    void testGetSubscriptionOptions_AfterUpdate() throws Exception {
        logger.info("=== Test 6: Get subscription options after update ===");
        
        String setupId = "test-setup-6";
        String queueName = "test-queue";
        String groupName = "test-group-6";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 45)
            .put("heartbeatTimeoutSeconds", 200);
        
        VertxTestContext updateContext = new VertxTestContext();
        
        // First, update
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(updateContext.succeedingThenComplete());
        
        Assertions.assertTrue(updateContext.awaitCompletion(10, TimeUnit.SECONDS));
        
        // Then, retrieve
        VertxTestContext getContext = new VertxTestContext();
        
        client.get(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onComplete(getContext.succeeding(response -> getContext.verify(() -> {
                Assertions.assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                
                JsonObject options = body.getJsonObject("subscriptionOptions");
                Assertions.assertEquals("FROM_BEGINNING", options.getString("startPosition"));
                Assertions.assertEquals(45, options.getInteger("heartbeatIntervalSeconds"));
                Assertions.assertEquals(200, options.getInteger("heartbeatTimeoutSeconds"));
                
                logger.info("✅ Retrieved subscription options match updated values");
                getContext.completeNow();
            })));
        
        Assertions.assertTrue(getContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 7: Delete subscription options")
    void testDeleteSubscriptionOptions() throws Exception {
        logger.info("=== Test 7: Delete subscription options ===");
        
        String setupId = "test-setup-7";
        String queueName = "test-queue";
        String groupName = "test-group-7";
        
        // First, create subscription options
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING");
        
        VertxTestContext updateContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(updateContext.succeedingThenComplete());
        
        Assertions.assertTrue(updateContext.awaitCompletion(10, TimeUnit.SECONDS));
        
        // Then, delete
        VertxTestContext deleteContext = new VertxTestContext();
        
        client.delete(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onComplete(deleteContext.succeeding(response -> deleteContext.verify(() -> {
                Assertions.assertEquals(204, response.statusCode());
                
                logger.info("✅ Subscription options deleted successfully");
                deleteContext.completeNow();
            })));
        
        Assertions.assertTrue(deleteContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 8: Delete non-existent subscription options returns 404")
    void testDeleteSubscriptionOptions_NotFound() throws Exception {
        logger.info("=== Test 8: Delete non-existent subscription options ===");
        
        String setupId = "nonexistent-setup";
        String queueName = "nonexistent-queue";
        String groupName = "nonexistent-group";
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.delete(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(404, response.statusCode());
                
                logger.info("✅ Correctly returned 404 for non-existent subscription");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
    
    @Test
    @DisplayName("Test 9: Invalid start position returns 400")
    void testUpdateSubscriptionOptions_InvalidPosition() throws Exception {
        logger.info("=== Test 9: Invalid start position ===");
        
        String setupId = "test-setup-9";
        String queueName = "test-queue";
        String groupName = "test-group-9";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "INVALID_POSITION");
        
        VertxTestContext testContext = new VertxTestContext();
        
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(400, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                Assertions.assertTrue(body.containsKey("error"));
                
                logger.info("✅ Correctly rejected invalid start position");
                testContext.completeNow();
            })));
        
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}
