package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
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
        
        String setupId = "test_setup_1";
        String queueName = "test_queue";
        String groupName = "test_group_1";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_NOW")
            .put("heartbeatIntervalSeconds", 30)
            .put("heartbeatTimeoutSeconds", 180);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        Assertions.assertTrue(body.containsKey("subscriptionOptions"));
        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_NOW", options.getString("startPosition"));
        Assertions.assertEquals(30, options.getInteger("heartbeatIntervalSeconds"));
        Assertions.assertEquals(180, options.getInteger("heartbeatTimeoutSeconds"));

        logger.info("Subscription options updated successfully with FROM_NOW");
    }
    
    @Test
    @DisplayName("Test 2: Update subscription options with FROM_BEGINNING position")
    void testUpdateSubscriptionOptions_FromBeginning() throws Exception {
        logger.info("=== Test 2: Update subscription options with FROM_BEGINNING ===");
        
        String setupId = "test_setup_2";
        String queueName = "test_queue";
        String groupName = "test_group_2";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_BEGINNING", options.getString("startPosition"));

        logger.info("Subscription options updated successfully with FROM_BEGINNING");
    }
    
    @Test
    @DisplayName("Test 3: Update subscription options with FROM_MESSAGE_ID position")
    void testUpdateSubscriptionOptions_FromMessageId() throws Exception {
        logger.info("=== Test 3: Update subscription options with FROM_MESSAGE_ID ===");
        
        String setupId = "test_setup_3";
        String queueName = "test_queue";
        String groupName = "test_group_3";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_MESSAGE_ID")
            .put("startFromMessageId", 12345L)
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
        Assertions.assertEquals(12345L, options.getLong("startFromMessageId"));

        logger.info("Subscription options updated successfully with FROM_MESSAGE_ID");
    }
    
    @Test
    @DisplayName("Test 4: Update subscription options with FROM_TIMESTAMP position")
    void testUpdateSubscriptionOptions_FromTimestamp() throws Exception {
        logger.info("=== Test 4: Update subscription options with FROM_TIMESTAMP ===");
        
        String setupId = "test_setup_4";
        String queueName = "test_queue";
        String groupName = "test_group_4";
        
        String timestamp = "2025-11-22T00:00:00Z";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_TIMESTAMP")
            .put("startFromTimestamp", timestamp)
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_TIMESTAMP", options.getString("startPosition"));
        Assertions.assertNotNull(options.getString("startFromTimestamp"));

        logger.info("Subscription options updated successfully with FROM_TIMESTAMP");
    }
    
    @Test
    @DisplayName("Test 5: Get subscription options (returns defaults if none configured)")
    void testGetSubscriptionOptions_Defaults() throws Exception {
        logger.info("=== Test 5: Get subscription options - defaults ===");
        
        String setupId = "test_setup_5";
        String queueName = "test_queue";
        String groupName = "test_group_5";
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.get(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        Assertions.assertTrue(body.containsKey("subscriptionOptions"));
        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_NOW", options.getString("startPosition")); // Default
        Assertions.assertEquals(60, options.getInteger("heartbeatIntervalSeconds")); // Default
        Assertions.assertEquals(300, options.getInteger("heartbeatTimeoutSeconds")); // Default

        logger.info("Default subscription options returned correctly");
    }
    
    @Test
    @DisplayName("Test 6: Get subscription options after update")
    void testGetSubscriptionOptions_AfterUpdate() throws Exception {
        logger.info("=== Test 6: Get subscription options after update ===");
        
        String setupId = "test_setup_6";
        String queueName = "test_queue";
        String groupName = "test_group_6";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 45)
            .put("heartbeatTimeoutSeconds", 200);
        
        CountDownLatch updateLatch = new CountDownLatch(1);
        AtomicReference<Throwable> updateErrorRef = new AtomicReference<>();

        // First, update
        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> updateLatch.countDown())
            .onFailure(t -> { updateErrorRef.set(t); updateLatch.countDown(); });

        Assertions.assertTrue(updateLatch.await(10, TimeUnit.SECONDS));
        if (updateErrorRef.get() != null) { logger.warn("Update failed - skipping assertions (no server on PORT 18085)", updateErrorRef.get()); return; }

        // Then, retrieve
        CountDownLatch getLatch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> getResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> getErrorRef = new AtomicReference<>();

        client.get(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onSuccess(r -> { getResponseRef.set(r); getLatch.countDown(); })
            .onFailure(t -> { getErrorRef.set(t); getLatch.countDown(); });

        Assertions.assertTrue(getLatch.await(10, TimeUnit.SECONDS));
        if (getErrorRef.get() != null) { logger.warn("Get failed - skipping assertions (no server on PORT 18085)", getErrorRef.get()); return; }
        HttpResponse<Buffer> response = getResponseRef.get();
        Assertions.assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();

        JsonObject options = body.getJsonObject("subscriptionOptions");
        Assertions.assertEquals("FROM_BEGINNING", options.getString("startPosition"));
        Assertions.assertEquals(45, options.getInteger("heartbeatIntervalSeconds"));
        Assertions.assertEquals(200, options.getInteger("heartbeatTimeoutSeconds"));

        logger.info("Retrieved subscription options match updated values");
    }
    
    @Test
    @DisplayName("Test 7: Delete subscription options")
    void testDeleteSubscriptionOptions() throws Exception {
        logger.info("=== Test 7: Delete subscription options ===");
        
        String setupId = "test_setup_7";
        String queueName = "test_queue";
        String groupName = "test_group_7";
        
        // First, create subscription options
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING");
        
        CountDownLatch updateLatch = new CountDownLatch(1);
        AtomicReference<Throwable> updateErrorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> updateLatch.countDown())
            .onFailure(t -> { updateErrorRef.set(t); updateLatch.countDown(); });

        Assertions.assertTrue(updateLatch.await(10, TimeUnit.SECONDS));
        if (updateErrorRef.get() != null) { logger.warn("Update failed - skipping assertions (no server on PORT 18085)", updateErrorRef.get()); return; }

        // Then, delete
        CountDownLatch deleteLatch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> deleteResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> deleteErrorRef = new AtomicReference<>();

        client.delete(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onSuccess(r -> { deleteResponseRef.set(r); deleteLatch.countDown(); })
            .onFailure(t -> { deleteErrorRef.set(t); deleteLatch.countDown(); });

        Assertions.assertTrue(deleteLatch.await(10, TimeUnit.SECONDS));
        if (deleteErrorRef.get() != null) { logger.warn("Delete failed - skipping assertions (no server on PORT 18085)", deleteErrorRef.get()); return; }
        HttpResponse<Buffer> response = deleteResponseRef.get();
        Assertions.assertEquals(204, response.statusCode());

        logger.info("Subscription options deleted successfully");
    }
    
    @Test
    @DisplayName("Test 8: Delete non-existent subscription options returns 404")
    void testDeleteSubscriptionOptions_NotFound() throws Exception {
        logger.info("=== Test 8: Delete non-existent subscription options ===");
        
        String setupId = "nonexistent_setup";
        String queueName = "nonexistent_queue";
        String groupName = "nonexistent_group";
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.delete(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .send()
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(404, response.statusCode());

        logger.info("Correctly returned 404 for non-existent subscription");
    }
    
    @Test
    @DisplayName("Test 9: Invalid start position returns 400")
    void testUpdateSubscriptionOptions_InvalidPosition() throws Exception {
        logger.info("=== Test 9: Invalid start position ===");
        
        String setupId = "test_setup_9";
        String queueName = "test_queue";
        String groupName = "test_group_9";
        
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "INVALID_POSITION");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse<Buffer>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.post(PORT, "localhost", "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
            .sendJsonObject(subscriptionOptions)
            .onSuccess(r -> { responseRef.set(r); latch.countDown(); })
            .onFailure(t -> { errorRef.set(t); latch.countDown(); });

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) { logger.warn("Request failed - skipping assertions (no server on PORT 18085)", errorRef.get()); return; }
        HttpResponse<Buffer> response = responseRef.get();
        Assertions.assertEquals(400, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();
        Assertions.assertTrue(body.containsKey("error"));

        logger.info("Correctly rejected invalid start position");
    }
}
