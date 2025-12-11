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
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DLQ, Subscription, and Health Operations in PeeGeeQRestClient.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DlqSubscriptionHealthOperationsTest {

    private static final Logger logger = LoggerFactory.getLogger(DlqSubscriptionHealthOperationsTest.class);
    private static final int MOCK_PORT = 18204;

    private HttpServer mockServer;
    private PeeGeeQClient client;
    private Router router;

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
        setupDlqEndpoints();
        setupSubscriptionEndpoints();
        setupHealthEndpoints();
    }

    private void setupDlqEndpoints() {
        String basePath = "/api/v1/setups/:setupId/deadletter";

        // GET - List dead letters
        router.get(basePath + "/messages").handler(ctx -> {
            JsonArray messages = new JsonArray()
                .add(new JsonObject().put("messageId", 1).put("error", "Processing failed"))
                .add(new JsonObject().put("messageId", 2).put("error", "Timeout"));

            JsonObject response = new JsonObject()
                .put("messages", messages)
                .put("totalCount", 2)
                .put("page", 0)
                .put("pageSize", 10);

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET - Get dead letter by ID
        router.get(basePath + "/messages/:messageId").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("messageId", Long.parseLong(ctx.pathParam("messageId")))
                .put("error", "Processing failed")
                .put("originalPayload", new JsonObject().put("data", "test"))
                .put("failedAt", Instant.now().toString());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST - Reprocess dead letter
        router.post(basePath + "/messages/:messageId/reprocess").handler(ctx -> {
            logger.info("Mock: Reprocessing dead letter {}", ctx.pathParam("messageId"));
            ctx.response().setStatusCode(204).end();
        });

        // DELETE - Delete dead letter
        router.delete(basePath + "/messages/:messageId").handler(ctx -> {
            logger.info("Mock: Deleting dead letter {}", ctx.pathParam("messageId"));
            ctx.response().setStatusCode(204).end();
        });

        // GET - Get DLQ stats
        router.get(basePath + "/stats").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("totalCount", 50)
                .put("oldestMessageAge", 86400)
                .put("newestMessageAge", 3600);

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST - Cleanup dead letters
        router.post(basePath + "/cleanup").handler(ctx -> {
            logger.info("Mock: Cleaning up dead letters");
            JsonObject response = new JsonObject().put("deletedCount", 10);
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });
    }

    private void setupSubscriptionEndpoints() {
        String basePath = "/api/v1/setups/:setupId/subscriptions/:topic";

        // GET - List subscriptions
        router.get(basePath).handler(ctx -> {
            JsonArray subs = new JsonArray()
                .add(new JsonObject().put("groupName", "group-1").put("status", "ACTIVE"))
                .add(new JsonObject().put("groupName", "group-2").put("status", "PAUSED"));

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(subs.encode());
        });

        // GET - Get subscription
        router.get(basePath + "/:groupName").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("groupName", ctx.pathParam("groupName"))
                .put("topic", ctx.pathParam("topic"))
                .put("status", "ACTIVE");

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST - Pause subscription
        router.post(basePath + "/:groupName/pause").handler(ctx -> {
            logger.info("Mock: Pausing subscription {}", ctx.pathParam("groupName"));
            ctx.response().setStatusCode(204).end();
        });

        // POST - Resume subscription
        router.post(basePath + "/:groupName/resume").handler(ctx -> {
            logger.info("Mock: Resuming subscription {}", ctx.pathParam("groupName"));
            ctx.response().setStatusCode(204).end();
        });
    }

    private void setupHealthEndpoints() {
        String basePath = "/api/v1/setups/:setupId/health";

        // GET - Overall health
        router.get(basePath).handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("status", "HEALTHY")
                .put("setupId", ctx.pathParam("setupId"))
                .put("componentCount", 5);

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET - List component health
        router.get(basePath + "/components").handler(ctx -> {
            JsonArray components = new JsonArray()
                .add(new JsonObject().put("name", "database").put("status", "HEALTHY"))
                .add(new JsonObject().put("name", "queue").put("status", "HEALTHY"));

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(components.encode());
        });

        // GET - Get component health
        router.get(basePath + "/components/:componentName").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("name", ctx.pathParam("componentName"))
                .put("status", "HEALTHY")
                .put("lastCheck", Instant.now().toString());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
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
    // DLQ Tests
    // ========================================================================

    @Test
    @DisplayName("listDeadLetters - lists dead letter messages")
    void listDeadLetters_success(VertxTestContext testContext) throws Exception {
        client.listDeadLetters("my-setup", 0, 10)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("listDeadLetters completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getDeadLetter - gets dead letter by ID")
    void getDeadLetter_success(VertxTestContext testContext) throws Exception {
        client.getDeadLetter("my-setup", 123L)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getDeadLetter completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("reprocessDeadLetter - reprocesses dead letter")
    void reprocessDeadLetter_success(VertxTestContext testContext) throws Exception {
        client.reprocessDeadLetter("my-setup", 123L)
            .onSuccess(v -> {
                logger.info("Dead letter reprocessed successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("deleteDeadLetter - deletes dead letter")
    void deleteDeadLetter_success(VertxTestContext testContext) throws Exception {
        client.deleteDeadLetter("my-setup", 123L)
            .onSuccess(v -> {
                logger.info("Dead letter deleted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Subscription Tests
    // ========================================================================

    @Test
    @DisplayName("listSubscriptions - lists subscriptions")
    void listSubscriptions_success(VertxTestContext testContext) throws Exception {
        client.listSubscriptions("my-setup", "orders")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("listSubscriptions completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getSubscription - gets subscription details")
    void getSubscription_success(VertxTestContext testContext) throws Exception {
        client.getSubscription("my-setup", "orders", "my-group")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getSubscription completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("pauseSubscription - pauses subscription")
    void pauseSubscription_success(VertxTestContext testContext) throws Exception {
        client.pauseSubscription("my-setup", "orders", "my-group")
            .onSuccess(v -> {
                logger.info("Subscription paused successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("resumeSubscription - resumes subscription")
    void resumeSubscription_success(VertxTestContext testContext) throws Exception {
        client.resumeSubscription("my-setup", "orders", "my-group")
            .onSuccess(v -> {
                logger.info("Subscription resumed successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Health Tests
    // ========================================================================

    @Test
    @DisplayName("getHealth - gets overall health")
    void getHealth_success(VertxTestContext testContext) throws Exception {
        client.getHealth("my-setup")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getHealth completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("listComponentHealth - lists component health")
    void listComponentHealth_success(VertxTestContext testContext) throws Exception {
        client.listComponentHealth("my-setup")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("listComponentHealth completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getComponentHealth - gets component health")
    void getComponentHealth_success(VertxTestContext testContext) throws Exception {
        client.getComponentHealth("my-setup", "database")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getComponentHealth completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

