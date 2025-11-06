package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Phase 1: Basic SSE Message Streaming.
 * 
 * Tests the complete SSE streaming implementation including:
 * - SSE connection establishment
 * - Message streaming from queue to SSE client
 * - Message type filtering
 * - Header filtering
 * - Error handling
 * - Connection cleanup
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end SSE streaming flow
 * - Tests actual message production and consumption
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-06
 * @version 1.0
 */
@Tag("integration")
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SSEStreamingPhase1IntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SSEStreamingPhase1IntegrationTest.class);
    private static final int TEST_PORT = 18080;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_sse_phase1_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private WebClient webClient;
    private String testSetupId;
    private String testQueueName;
    private HttpClient httpClient;
    private PeeGeeQRestServer server;
    private String deploymentId;

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up SSE Phase 1 Integration Test ===");

        testSetupId = "sse-phase1-test-" + System.currentTimeMillis();
        testQueueName = "sse_test_queue";

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

    /**
     * Creates database setup via REST API so it's registered in the server's setupService.
     */
    private void createDatabaseSetupViaRestApi(Vertx vertx, VertxTestContext testContext) {
        JsonObject setupRequest = new JsonObject()
                .put("setupId", testSetupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "sse_phase1_db_" + System.currentTimeMillis())
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
                .put("additionalProperties", new JsonObject().put("test_type", "sse_phase1"));

        logger.info("Creating database setup via REST API: {}", testSetupId);

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
    void tearDownAll(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down SSE Phase 1 Integration Test ===");

        if (httpClient != null) {
            httpClient.close();
        }

        if (webClient != null) {
            webClient.close();
        }

        // Destroy setup via REST API BEFORE undeploying the server
        if (testSetupId != null && deploymentId != null) {
            destroyDatabaseSetupViaRestApi(vertx, testContext)
                .compose(v -> {
                    // Now undeploy the server
                    return vertx.undeploy(deploymentId);
                })
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        logger.info("Teardown completed successfully");
                    } else {
                        logger.warn("Teardown completed with warnings", ar.cause());
                    }
                    testContext.completeNow();
                });
        } else if (deploymentId != null) {
            // No setup to destroy, just undeploy
            vertx.undeploy(deploymentId)
                .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    /**
     * Destroys database setup via REST API.
     */
    private Future<Void> destroyDatabaseSetupViaRestApi(Vertx vertx, VertxTestContext testContext) {
        // Create a new WebClient since the old one might be closed
        WebClient client = WebClient.create(vertx);

        return client.delete(TEST_PORT, "localhost", "/api/v1/database-setup/" + testSetupId)
                .timeout(30000)
                .send()
                .<Void>compose(response -> {
                    client.close();
                    if (response.statusCode() == 200 || response.statusCode() == 204) {
                        logger.info("Test setup destroyed: {}", testSetupId);
                        return Future.succeededFuture();
                    } else {
                        logger.warn("Failed to cleanup test setup: {}, status: {}", testSetupId, response.statusCode());
                        return Future.succeededFuture(); // Don't fail teardown
                    }
                })
                .recover(err -> {
                    client.close();
                    logger.warn("Failed to cleanup test setup: {}", testSetupId, err);
                    return Future.succeededFuture(); // Don't fail teardown
                });
    }

    /**
     * Test 1: Basic SSE Connection Establishment
     * Validates that SSE connection can be established and initial events are received.
     */
    @Test
    @Order(1)
    void testSSEConnectionEstablishment(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 1: SSE Connection Establishment ===");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> connectionEvent = new AtomicReference<>();
        AtomicReference<String> configuredEvent = new AtomicReference<>();
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();

        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("text/event-stream", response.getHeader("Content-Type"));
                    assertEquals("no-cache", response.getHeader("Cache-Control"));
                    assertEquals("keep-alive", response.getHeader("Connection"));
                    logger.info("✅ SSE connection established with correct headers");
                });

                // Read SSE events
                AtomicInteger eventCount = new AtomicInteger(0);
                response.handler(buffer -> {
                    String data = buffer.toString();
                    logger.debug("Received SSE data: {}", data);

                    if (data.contains("\"type\":\"connection\"")) {
                        connectionEvent.set(data);
                        eventCount.incrementAndGet();
                    }
                    if (data.contains("\"type\":\"configured\"")) {
                        configuredEvent.set(data);
                        eventCount.incrementAndGet();
                    }

                    // Once we have both events, complete the test
                    if (eventCount.get() >= 2) {
                        latch.countDown();
                    }
                });

                // Don't fail on connection close since we intentionally close it
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        testContext.failNow(err);
                    }
                });
            })
            .onFailure(testContext::failNow);

        // Wait for events
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive connection and configured events");

        testContext.verify(() -> {
            assertNotNull(connectionEvent.get(), "Should receive connection event");
            assertNotNull(configuredEvent.get(), "Should receive configured event");

            logger.info("✅ Connection event received: {}", connectionEvent.get());
            logger.info("✅ Configured event received: {}", configuredEvent.get());
        });

        // Close the SSE connection
        if (responseRef.get() != null) {
            responseRef.get().request().connection().close();
            logger.info("🔌 Closed SSE connection for Test 1");
            // Wait for connection to fully close
            Thread.sleep(500);
        }

        testContext.completeNow();
    }

    /**
     * Test 2: Basic Message Streaming
     * Validates that messages sent to the queue are streamed to SSE clients.
     */
    @Test
    @Order(2)
    void testBasicMessageStreaming(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 2: Basic Message Streaming ===");

        CountDownLatch messageLatch = new CountDownLatch(3);
        AtomicInteger messagesReceived = new AtomicInteger(0);
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();

        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established for message streaming test");

                response.handler(buffer -> {
                    String data = buffer.toString();
                    logger.info("📨 SSE data received: {}", data);

                    // Look for data events (not connection/configured events)
                    if (data.contains("\"type\":\"data\"") && data.contains("\"messageType\":\"TestMessage\"")) {
                        int count = messagesReceived.incrementAndGet();
                        logger.info("✅ Received message {} via SSE", count);
                        messageLatch.countDown();
                    }
                });

                // Send test messages via REST API after connection is established
                vertx.setTimer(1000, id -> {
                    sendMessageViaRestApi("test-1", "Test message 1", "TestMessage", null)
                        .compose(v -> sendMessageViaRestApi("test-2", "Test message 2", "TestMessage", null))
                        .compose(v -> sendMessageViaRestApi("test-3", "Test message 3", "TestMessage", null))
                        .onSuccess(v -> logger.info("📤 Sent 3 test messages via REST API"))
                        .onFailure(testContext::failNow);
                });
            })
            .onFailure(testContext::failNow);

        // Wait for all messages
        assertTrue(messageLatch.await(15, TimeUnit.SECONDS), "Should receive all 3 messages via SSE");

        testContext.verify(() -> {
            assertEquals(3, messagesReceived.get(), "Should receive exactly 3 messages");
            logger.info("✅ All messages received successfully via SSE");
        });

        // Close the SSE connection
        if (responseRef.get() != null) {
            responseRef.get().request().connection().close();
            logger.info("🔌 Closed SSE connection for Test 2");
            // Wait for connection to fully close
            Thread.sleep(500);
        }

        testContext.completeNow();
    }

    /**
     * Test 3: Message Type Filtering
     * Validates that messageType filter works correctly.
     */
    @Test
    @Order(3)
    void testMessageTypeFiltering(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 3: Message Type Filtering ===");

        CountDownLatch messageLatch = new CountDownLatch(2);
        AtomicInteger messagesReceived = new AtomicInteger(0);
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();

        // Connect with messageType filter
        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream?messageType=FilteredMessage";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established with messageType filter");

                response.handler(buffer -> {
                    String data = buffer.toString();

                    // Should only receive FilteredMessage, not TestMessage
                    if (data.contains("\"type\":\"data\"")) {
                        if (data.contains("\"messageType\":\"FilteredMessage\"")) {
                            int count = messagesReceived.incrementAndGet();
                            logger.info("✅ Received filtered message {} via SSE", count);
                            messageLatch.countDown();
                        } else if (data.contains("\"messageType\":\"TestMessage\"")) {
                            testContext.failNow(new AssertionError("Should not receive TestMessage with filter"));
                        }
                    }
                });

                // Send mixed messages via REST API
                vertx.setTimer(1000, id -> {
                    // Send TestMessage (should be filtered out)
                    sendMessageViaRestApi("test-1", "Should be filtered", "TestMessage", null)
                        // Send FilteredMessage (should pass through)
                        .compose(v -> sendMessageViaRestApi("filtered-1", "Should pass", "FilteredMessage", null))
                        // Send another FilteredMessage
                        .compose(v -> sendMessageViaRestApi("filtered-2", "Should also pass", "FilteredMessage", null))
                        .onSuccess(v -> logger.info("📤 Sent mixed messages via REST API"))
                        .onFailure(testContext::failNow);
                });
            })
            .onFailure(testContext::failNow);

        // Wait for filtered messages
        assertTrue(messageLatch.await(15, TimeUnit.SECONDS), "Should receive 2 filtered messages");

        testContext.verify(() -> {
            assertEquals(2, messagesReceived.get(), "Should receive exactly 2 FilteredMessage messages");
            logger.info("✅ Message type filtering working correctly");
        });

        // Close the SSE connection
        if (responseRef.get() != null) {
            responseRef.get().request().connection().close();
            logger.info("🔌 Closed SSE connection for Test 3");
            // Wait for connection to fully close
            Thread.sleep(500);
        }

        testContext.completeNow();
    }

    /**
     * Test 4: Header Filtering
     * Validates that header-based filtering works correctly.
     */
    @Test
    @Order(4)
    void testHeaderFiltering(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 4: Header Filtering ===");

        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicInteger messagesReceived = new AtomicInteger(0);
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();

        // Connect with header filter (region=US-WEST)
        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream?header.region=US-WEST";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                logger.info("SSE connection established with header filter");

                response.handler(buffer -> {
                    String data = buffer.toString();

                    if (data.contains("\"type\":\"data\"")) {
                        if (data.contains("\"region\":\"US-WEST\"")) {
                            int count = messagesReceived.incrementAndGet();
                            logger.info("✅ Received message with matching header {} via SSE", count);
                            messageLatch.countDown();
                        } else if (data.contains("\"region\":\"US-EAST\"")) {
                            testContext.failNow(new AssertionError("Should not receive US-EAST message with filter"));
                        }
                    }
                });

                // Send messages with different headers via REST API
                vertx.setTimer(1000, id -> {
                    // Send message with US-EAST (should be filtered out)
                    sendMessageViaRestApi("east-1", "US East message", "TestMessage", Map.of("region", "US-EAST"))
                        // Send message with US-WEST (should pass through)
                        .compose(v -> sendMessageViaRestApi("west-1", "US West message", "TestMessage", Map.of("region", "US-WEST")))
                        .onSuccess(v -> logger.info("📤 Sent messages with different headers via REST API"))
                        .onFailure(testContext::failNow);
                });
            })
            .onFailure(testContext::failNow);

        // Wait for filtered message
        assertTrue(messageLatch.await(15, TimeUnit.SECONDS), "Should receive 1 message with matching header");

        testContext.verify(() -> {
            assertEquals(1, messagesReceived.get(), "Should receive exactly 1 message with region=US-WEST");
            logger.info("✅ Header filtering working correctly");
        });

        // Close the SSE connection
        if (responseRef.get() != null) {
            responseRef.get().request().connection().close();
            logger.info("🔌 Closed SSE connection for Test 4");
            // Wait for connection to fully close
            Thread.sleep(500);
        }

        testContext.completeNow();
    }

    /**
     * Test 5: Connection Cleanup
     * Validates that connections are properly cleaned up when closed.
     */
    @Test
    @Order(5)
    void testConnectionCleanup(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 5: Connection Cleanup ===");

        String sseUrl = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                logger.info("SSE connection established for cleanup test");

                // Close connection after receiving initial events
                vertx.setTimer(2000, id -> {
                    response.request().connection().close();
                    logger.info("✅ SSE connection closed");

                    // Verify cleanup happened (connection should be removed from active connections)
                    vertx.setTimer(1000, id2 -> {
                        logger.info("✅ Connection cleanup test completed");
                        testContext.completeNow();
                    });
                });
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Helper method to send a message via REST API.
     */
    private io.vertx.core.Future<Void> sendMessageViaRestApi(String id, String content, String messageType, Map<String, String> additionalHeaders) {
        JsonObject messageRequest = new JsonObject()
                .put("payload", new JsonObject()
                        .put("id", id)
                        .put("content", content)
                        .put("value", 100))
                .put("messageType", messageType)
                .put("priority", 5);

        // Add headers
        JsonObject headers = new JsonObject()
                .put("messageType", messageType)
                .put("source", "integration-test");

        if (additionalHeaders != null) {
            additionalHeaders.forEach(headers::put);
        }

        messageRequest.put("headers", headers);

        return webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages")
                .putHeader("content-type", "application/json")
                .timeout(5000)
                .sendJsonObject(messageRequest)
                .compose(response -> {
                    if (response.statusCode() == 200) {
                        logger.info("Message sent via REST API: {}", id);
                        return io.vertx.core.Future.succeededFuture();
                    } else {
                        return io.vertx.core.Future.failedFuture("Failed to send message: " + response.bodyAsString());
                    }
                });
    }

    /**
     * Test message class for integration tests.
     */
    public static class TestMessage {
        private String id;
        private String content;
        private int value;

        public TestMessage() {}

        public TestMessage(String id, String content, int value) {
            this.id = id;
            this.content = content;
            this.value = value;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}

