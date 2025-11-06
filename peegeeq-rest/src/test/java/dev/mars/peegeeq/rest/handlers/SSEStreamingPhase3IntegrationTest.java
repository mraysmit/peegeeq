package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SSE Phase 3: Batching Support.
 *
 * Tests the SSE batching mechanism where messages are accumulated and sent
 * in batches based on batch size and timeout configuration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-06
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
public class SSEStreamingPhase3IntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEStreamingPhase3IntegrationTest.class);
    
    private static final int TEST_PORT = 18082;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_sse_phase3_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);
    
    // Test instance variables
    private WebClient webClient;
    private String testSetupId;
    private String testQueueName;
    private HttpClient httpClient;
    private PeeGeeQRestServer server;
    private String deploymentId;
    
    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up SSE Phase 3 Integration Test ===");
        
        testSetupId = "sse-phase3-test-" + System.currentTimeMillis();
        testQueueName = "sse_batch_test_queue";
        
        // Deploy REST server first
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
                    // Now create database setup via REST API
                    createDatabaseSetupViaRestApi(vertx, testContext);
                });
            })
            .onFailure(testContext::failNow);
    }
    
    private void createDatabaseSetupViaRestApi(Vertx vertx, VertxTestContext testContext) {
        logger.info("Creating database setup via REST API: {}", testSetupId);

        JsonObject setupRequest = new JsonObject()
                .put("setupId", testSetupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "sse_phase3_db_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "public")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new JsonArray()
                        .add(new JsonObject()
                                .put("queueName", testQueueName)
                                .put("maxRetries", 3)
                                .put("visibilityTimeout", 30)))
                .put("eventStores", new JsonArray())
                .put("additionalProperties", new JsonObject().put("test_type", "sse_phase3"));

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(setupRequest)
                .onSuccess(response -> {
                    logger.info("Setup creation response status: {}", response.statusCode());
                    logger.info("Setup creation response body: {}", response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        logger.info("Database setup created successfully: {}", body.getString("setupId"));
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new Exception("Failed to create setup: " + response.bodyAsString()));
                    }
                })
                .onFailure(testContext::failNow);
    }
    
    @AfterAll
    void tearDownAll(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Tearing down SSE Phase 3 Integration Test ===");
        
        // Undeploy server
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> {
                    if (httpClient != null) {
                        httpClient.close();
                    }
                    if (webClient != null) {
                        webClient.close();
                    }
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
    }
    
    /**
     * Test 1: Verify batch size configuration works correctly.
     * Send exactly batchSize messages and verify they arrive in a single batch event.
     */
    @Test
    @Order(1)
    void testBatchSizeConfiguration(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 1: Batch Size Configuration ===");

        int batchSize = 3;
        CountDownLatch batchLatch = new CountDownLatch(1);
        AtomicReference<JsonObject> receivedBatch = new AtomicReference<>();
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();
        
        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream?batchSize=" + batchSize;
        
        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established with batchSize={}", batchSize);
                
                response.handler(buffer -> {
                    String data = buffer.toString();
                    logger.info("📨 SSE data received: {}", data);
                    
                    // Parse SSE events
                    if (data.contains("event: batch")) {
                        // Extract batch data
                        String[] lines = data.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].startsWith("data: ")) {
                                String jsonData = lines[i].substring(6);
                                JsonObject batchEvent = new JsonObject(jsonData);
                                receivedBatch.set(batchEvent);
                                logger.info("✓ Received batch event with {} messages", 
                                           batchEvent.getInteger("messageCount"));
                                batchLatch.countDown();
                                break;
                            }
                        }
                    }
                });
            })
            .onFailure(testContext::failNow);
        
        // Wait for connection to be established
        Thread.sleep(1000);
        
        // Send exactly batchSize messages
        for (int i = 1; i <= batchSize; i++) {
            sendMessage(vertx, "batch-test-" + i, 100);
            Thread.sleep(100); // Small delay between messages
        }
        
        logger.info("✓ Sent {} messages via REST API", batchSize);
        
        // Wait for batch event
        assertTrue(batchLatch.await(10, TimeUnit.SECONDS), "Batch event should be received");
        
        // Verify batch content
        JsonObject batch = receivedBatch.get();
        assertNotNull(batch, "Batch event should not be null");
        assertEquals(batchSize, batch.getInteger("messageCount"), "Batch should contain exactly " + batchSize + " messages");
        
        JsonArray messages = batch.getJsonArray("messages");
        assertNotNull(messages, "Batch messages array should not be null");
        assertEquals(batchSize, messages.size(), "Batch messages array should have " + batchSize + " elements");
        
        logger.info("✓ Batch size configuration test passed");
        
        // Close connection
        responseRef.get().request().connection().close();
        Thread.sleep(500);
        
        testContext.completeNow();
    }
    
    /**
     * Test 2: Verify batch timeout mechanism.
     * Send fewer messages than batchSize and verify they are sent after maxWaitTime expires.
     */
    @Test
    @Order(2)
    void testBatchTimeout(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 2: Batch Timeout ===");

        int batchSize = 5;
        long maxWaitTime = 2000; // 2 seconds
        int messagesToSend = 2; // Less than batch size

        CountDownLatch batchLatch = new CountDownLatch(1);
        AtomicReference<JsonObject> receivedBatch = new AtomicReference<>();
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();
        AtomicReference<Long> batchReceivedTime = new AtomicReference<>();

        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName +
                       "/stream?batchSize=" + batchSize + "&maxWait=" + maxWaitTime;

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established with batchSize={}, maxWait={}ms", batchSize, maxWaitTime);

                response.handler(buffer -> {
                    String data = buffer.toString();
                    logger.info("📨 SSE data received: {}", data);

                    // Parse SSE events
                    if (data.contains("event: batch")) {
                        batchReceivedTime.set(System.currentTimeMillis());
                        // Extract batch data
                        String[] lines = data.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].startsWith("data: ")) {
                                String jsonData = lines[i].substring(6);
                                JsonObject batchEvent = new JsonObject(jsonData);
                                receivedBatch.set(batchEvent);
                                logger.info("✓ Received batch event with {} messages after timeout",
                                           batchEvent.getInteger("messageCount"));
                                batchLatch.countDown();
                                break;
                            }
                        }
                    }
                });
            })
            .onFailure(testContext::failNow);

        // Wait for connection to be established
        Thread.sleep(1000);

        long sendStartTime = System.currentTimeMillis();

        // Send fewer messages than batch size
        for (int i = 1; i <= messagesToSend; i++) {
            sendMessage(vertx, "timeout-test-" + i, 200);
            Thread.sleep(100);
        }

        logger.info("✓ Sent {} messages (less than batch size {})", messagesToSend, batchSize);

        // Wait for batch event (should arrive after timeout)
        assertTrue(batchLatch.await(maxWaitTime + 5000, TimeUnit.MILLISECONDS),
                  "Batch event should be received after timeout");

        // Verify batch was sent due to timeout, not batch size
        long elapsedTime = batchReceivedTime.get() - sendStartTime;
        logger.info("Batch received after {} ms (expected ~{} ms)", elapsedTime, maxWaitTime);
        assertTrue(elapsedTime >= maxWaitTime - 500, // Allow 500ms tolerance
                  "Batch should be sent after timeout expires");

        // Verify batch content
        JsonObject batch = receivedBatch.get();
        assertNotNull(batch, "Batch event should not be null");
        assertEquals(messagesToSend, batch.getInteger("messageCount"),
                    "Batch should contain " + messagesToSend + " messages");

        logger.info("✓ Batch timeout test passed");

        // Close connection
        responseRef.get().request().connection().close();
        Thread.sleep(500);

        testContext.completeNow();
    }

    /**
     * Test 3: Verify single message mode (batchSize=1).
     * When batchSize is 1, messages should be sent immediately as individual events, not batches.
     */
    @Test
    @Order(3)
    void testSingleMessageMode(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 3: Single Message Mode (batchSize=1) ===");

        int messagesToSend = 3;
        CountDownLatch messageLatch = new CountDownLatch(messagesToSend);
        AtomicInteger messageEventCount = new AtomicInteger(0);
        AtomicInteger batchEventCount = new AtomicInteger(0);
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();

        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream?batchSize=1";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established with batchSize=1");

                response.handler(buffer -> {
                    String data = buffer.toString();
                    logger.info("📨 SSE data received: {}", data);

                    // Count event types
                    if (data.contains("event: message")) {
                        messageEventCount.incrementAndGet();
                        messageLatch.countDown();
                        logger.info("✓ Received individual message event ({}/{})",
                                   messageEventCount.get(), messagesToSend);
                    } else if (data.contains("event: batch")) {
                        batchEventCount.incrementAndGet();
                        logger.warn("❌ Received unexpected batch event");
                    }
                });
            })
            .onFailure(testContext::failNow);

        // Wait for connection to be established
        Thread.sleep(1000);

        // Send messages
        for (int i = 1; i <= messagesToSend; i++) {
            sendMessage(vertx, "single-test-" + i, 300);
            Thread.sleep(200);
        }

        logger.info("✓ Sent {} messages via REST API", messagesToSend);

        // Wait for all messages
        assertTrue(messageLatch.await(10, TimeUnit.SECONDS), "All messages should be received");

        // Verify we received individual message events, not batch events
        assertEquals(messagesToSend, messageEventCount.get(),
                    "Should receive " + messagesToSend + " individual message events");
        assertEquals(0, batchEventCount.get(), "Should not receive any batch events when batchSize=1");

        logger.info("✓ Single message mode test passed");

        // Close connection
        responseRef.get().request().connection().close();
        Thread.sleep(500);

        testContext.completeNow();
    }

    /**
     * Helper method to send a message via REST API.
     */
    private void sendMessage(Vertx vertx, String messageId, int value) {
        JsonObject message = new JsonObject()
            .put("id", messageId)
            .put("value", value)
            .put("content", "Test message " + messageId);

        JsonObject request = new JsonObject()
            .put("payload", message)
            .put("messageType", "TestMessage")
            .put("headers", new JsonObject()
                .put("source", "integration-test")
                .put("priority", "5"));

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages")
            .timeout(5000)
            .sendJsonObject(request)
            .onSuccess(response -> logger.info("Message sent via REST API: {}", messageId))
            .onFailure(err -> logger.error("Failed to send message: {}", err.getMessage()));
    }
}

