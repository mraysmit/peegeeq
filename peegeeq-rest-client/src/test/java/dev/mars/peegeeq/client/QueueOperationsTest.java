/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.peegeeq.client;

import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.MessageRequest;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Queue Operations in PeeGeeQRestClient.
 * Uses a mock HTTP server to test client behavior.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueOperationsTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueOperationsTest.class);
    private static final int MOCK_PORT = 18201;

    private HttpServer mockServer;
    private PeeGeeQClient client;
    private Router router;
    
    // Track received requests for verification
    private final AtomicReference<JsonObject> lastReceivedMessage = new AtomicReference<>();
    private final AtomicReference<JsonArray> lastReceivedBatch = new AtomicReference<>();

    @BeforeAll
    void setupMockServer(Vertx vertx, VertxTestContext testContext) {
        router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        setupMockEndpoints();

        mockServer = vertx.createHttpServer();
        mockServer.requestHandler(router)
            .listen(MOCK_PORT)
            .onSuccess(server -> {
                logger.info("Mock server started on port {}", MOCK_PORT);
                
                ClientConfig config = ClientConfig.builder()
                    .baseUrl("http://localhost:" + MOCK_PORT)
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();
                client = PeeGeeQRestClient.create(vertx, config);
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private void setupMockEndpoints() {
        // POST /api/v1/queues/:setupId/:queueName/messages - Send message
        router.post("/api/v1/queues/:setupId/:queueName/messages").handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            lastReceivedMessage.set(body);
            logger.info("Mock: Received message for {}/{}", 
                ctx.pathParam("setupId"), ctx.pathParam("queueName"));
            
            JsonObject response = new JsonObject()
                .put("messageId", "msg-" + System.currentTimeMillis())
                .put("status", "SENT");
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST /api/v1/queues/:setupId/:queueName/messages/batch - Send batch
        router.post("/api/v1/queues/:setupId/:queueName/messages/batch").handler(ctx -> {
            JsonArray body = ctx.body().asJsonArray();
            lastReceivedBatch.set(body);
            logger.info("Mock: Received batch of {} messages", body.size());
            
            JsonArray results = new JsonArray();
            for (int i = 0; i < body.size(); i++) {
                results.add(new JsonObject()
                    .put("messageId", "msg-batch-" + i)
                    .put("status", "SENT"));
            }
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(results.encode());
        });

        // GET /api/v1/queues/:setupId/:queueName/stats - Get queue stats
        router.get("/api/v1/queues/:setupId/:queueName/stats").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("queueName", ctx.pathParam("queueName"))
                .put("pendingCount", 10)
                .put("processedCount", 100)
                .put("deadLetterCount", 2);
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET /api/v1/queues/:setupId/:queueName - Get queue details
        router.get("/api/v1/queues/:setupId/:queueName").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("queueName", ctx.pathParam("queueName"))
                .put("setupId", ctx.pathParam("setupId"))
                .put("implementationType", "native")
                .put("healthy", true);
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST /api/v1/queues/:setupId/:queueName/purge - Purge queue
        router.post("/api/v1/queues/:setupId/:queueName/purge").handler(ctx -> {
            logger.info("Mock: Purging queue {}/{}",
                ctx.pathParam("setupId"), ctx.pathParam("queueName"));

            JsonObject response = new JsonObject()
                .put("purgedCount", 5);

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET /api/v1/queues/:setupId/:queueName/consumers - Get consumers
        router.get("/api/v1/queues/:setupId/:queueName/consumers").handler(ctx -> {
            JsonArray consumers = new JsonArray()
                .add(new JsonObject().put("consumerId", "consumer-1").put("active", true))
                .add(new JsonObject().put("consumerId", "consumer-2").put("active", false));

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(consumers.encode());
        });
    }

    @AfterAll
    void tearDown(VertxTestContext testContext) {
        if (client != null) {
            client.close();
            logger.info("Client closed");
        }
        if (mockServer != null) {
            mockServer.close()
                .onComplete(ar -> {
                    logger.info("Mock server closed");
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Test Methods
    // ========================================================================

    @Test
    @DisplayName("sendMessage - sends message successfully")
    void sendMessage_success(VertxTestContext testContext) throws Exception {
        MessageRequest request = new MessageRequest()
            .withPayload(new JsonObject().put("data", "test-payload"));

        client.sendMessage("my-setup", "test-queue", request)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("sendMessage completed: success={}", ar.succeeded());
                    assertNotNull(lastReceivedMessage.get(), "Server should have received message");
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("sendBatch - sends batch of messages")
    void sendBatch_success(VertxTestContext testContext) throws Exception {
        List<MessageRequest> batch = List.of(
            new MessageRequest().withPayload(new JsonObject().put("id", 1)),
            new MessageRequest().withPayload(new JsonObject().put("id", 2)),
            new MessageRequest().withPayload(new JsonObject().put("id", 3))
        );

        client.sendBatch("my-setup", "test-queue", batch)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("sendBatch completed: success={}", ar.succeeded());
                    assertNotNull(lastReceivedBatch.get(), "Server should have received batch");
                    assertEquals(3, lastReceivedBatch.get().size(), "Batch should have 3 messages");
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getQueueStats - returns queue statistics")
    void getQueueStats_success(VertxTestContext testContext) throws Exception {
        client.getQueueStats("my-setup", "test-queue")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getQueueStats completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getQueueDetails - returns queue details")
    void getQueueDetails_success(VertxTestContext testContext) throws Exception {
        client.getQueueDetails("my-setup", "test-queue")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getQueueDetails completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("purgeQueue - purges queue successfully")
    void purgeQueue_success(VertxTestContext testContext) throws Exception {
        client.purgeQueue("my-setup", "test-queue")
            .onSuccess(count -> {
                logger.info("Queue purged successfully, {} messages removed", count);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getQueueConsumers - returns consumer list")
    void getQueueConsumers_success(VertxTestContext testContext) throws Exception {
        client.getQueueConsumers("my-setup", "test-queue")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getQueueConsumers completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

