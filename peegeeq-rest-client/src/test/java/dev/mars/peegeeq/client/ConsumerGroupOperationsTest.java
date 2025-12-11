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
import dev.mars.peegeeq.client.dto.SubscriptionOptionsRequest;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Consumer Group Operations in PeeGeeQRestClient.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerGroupOperationsTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupOperationsTest.class);
    private static final int MOCK_PORT = 18202;

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
        String basePath = "/api/v1/queues/:setupId/:queueName/consumer-groups";
        
        // POST - Create consumer group
        router.post(basePath).handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            logger.info("Mock: Creating consumer group: {}", body.getString("groupName"));
            
            JsonObject response = new JsonObject()
                .put("groupName", body.getString("groupName"))
                .put("queueName", ctx.pathParam("queueName"))
                .put("memberCount", 0);
            
            ctx.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET - List consumer groups
        router.get(basePath).handler(ctx -> {
            JsonArray groups = new JsonArray()
                .add(new JsonObject().put("groupName", "group-1").put("memberCount", 2))
                .add(new JsonObject().put("groupName", "group-2").put("memberCount", 1));
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(groups.encode());
        });

        // GET - Get specific consumer group
        router.get(basePath + "/:groupName").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("groupName", ctx.pathParam("groupName"))
                .put("queueName", ctx.pathParam("queueName"))
                .put("memberCount", 3);
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // DELETE - Delete consumer group
        router.delete(basePath + "/:groupName").handler(ctx -> {
            logger.info("Mock: Deleting consumer group: {}", ctx.pathParam("groupName"));
            ctx.response().setStatusCode(204).end();
        });

        // POST - Join consumer group
        router.post(basePath + "/:groupName/members").handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            logger.info("Mock: Joining consumer group: {}", ctx.pathParam("groupName"));
            
            JsonObject response = new JsonObject()
                .put("memberId", "member-" + System.currentTimeMillis())
                .put("memberName", body.getString("memberName", "anonymous"))
                .put("groupName", ctx.pathParam("groupName"));
            
            ctx.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // DELETE - Leave consumer group
        router.delete(basePath + "/:groupName/members/:memberId").handler(ctx -> {
            logger.info("Mock: Member {} leaving group {}", 
                ctx.pathParam("memberId"), ctx.pathParam("groupName"));
            ctx.response().setStatusCode(204).end();
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
    @DisplayName("createConsumerGroup - creates group successfully")
    void createConsumerGroup_success(VertxTestContext testContext) throws Exception {
        client.createConsumerGroup("my-setup", "test-queue", "my-group")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("createConsumerGroup completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("listConsumerGroups - returns list of groups")
    void listConsumerGroups_success(VertxTestContext testContext) throws Exception {
        client.listConsumerGroups("my-setup", "test-queue")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("listConsumerGroups completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getConsumerGroup - returns group details")
    void getConsumerGroup_success(VertxTestContext testContext) throws Exception {
        client.getConsumerGroup("my-setup", "test-queue", "my-group")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getConsumerGroup completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("deleteConsumerGroup - deletes group successfully")
    void deleteConsumerGroup_success(VertxTestContext testContext) throws Exception {
        client.deleteConsumerGroup("my-setup", "test-queue", "my-group")
            .onSuccess(v -> {
                logger.info("Consumer group deleted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("joinConsumerGroup - joins group successfully")
    void joinConsumerGroup_success(VertxTestContext testContext) throws Exception {
        client.joinConsumerGroup("my-setup", "test-queue", "my-group", "my-member")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("joinConsumerGroup completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("leaveConsumerGroup - leaves group successfully")
    void leaveConsumerGroup_success(VertxTestContext testContext) throws Exception {
        client.leaveConsumerGroup("my-setup", "test-queue", "my-group", "member-123")
            .onSuccess(v -> {
                logger.info("Left consumer group successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

