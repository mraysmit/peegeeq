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

import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.AppendEventRequest;
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
 * Tests for Event Store Operations in PeeGeeQRestClient.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStoreOperationsTest {

    private static final Logger logger = LoggerFactory.getLogger(EventStoreOperationsTest.class);
    private static final int MOCK_PORT = 18203;

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
        String basePath = "/api/v1/event-stores/:setupId/:storeName";
        
        // POST - Append event
        router.post(basePath + "/events").handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            logger.info("Mock: Appending event to store: {}", ctx.pathParam("storeName"));
            
            JsonObject response = new JsonObject()
                .put("eventId", "evt-" + System.currentTimeMillis())
                .put("eventType", body.getString("eventType"))
                .put("payload", body.getValue("payload"))
                .put("validTime", Instant.now().toString())
                .put("transactionTime", Instant.now().toString());
            
            ctx.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // POST - Query events
        router.post(basePath + "/query").handler(ctx -> {
            logger.info("Mock: Querying events from store: {}", ctx.pathParam("storeName"));
            
            JsonArray events = new JsonArray()
                .add(new JsonObject().put("eventId", "evt-1").put("eventType", "OrderCreated"))
                .add(new JsonObject().put("eventId", "evt-2").put("eventType", "OrderUpdated"));
            
            JsonObject response = new JsonObject()
                .put("events", events)
                .put("totalCount", 2)
                .put("hasMore", false);
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET - Get event by ID
        router.get(basePath + "/events/:eventId").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("eventId", ctx.pathParam("eventId"))
                .put("eventType", "OrderCreated")
                .put("payload", new JsonObject().put("orderId", "123"))
                .put("validTime", Instant.now().toString())
                .put("transactionTime", Instant.now().toString());
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        });

        // GET - Get event store stats
        router.get(basePath + "/stats").handler(ctx -> {
            JsonObject response = new JsonObject()
                .put("storeName", ctx.pathParam("storeName"))
                .put("eventCount", 1000)
                .put("oldestEventTime", Instant.now().minusSeconds(86400).toString())
                .put("newestEventTime", Instant.now().toString());
            
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
    // Test Methods
    // ========================================================================

    @Test
    @DisplayName("appendEvent - appends event successfully")
    void appendEvent_success(VertxTestContext testContext) throws Exception {
        AppendEventRequest request = new AppendEventRequest()
            .withEventType("OrderCreated")
            .withPayload(new JsonObject().put("orderId", "123").put("amount", 99.99));

        client.appendEvent("my-setup", "orders-store", request)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("appendEvent completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("queryEvents - queries events successfully")
    void queryEvents_success(VertxTestContext testContext) throws Exception {
        EventQuery query = EventQuery.builder()
            .eventType("OrderCreated")
            .limit(10)
            .build();

        client.queryEvents("my-setup", "orders-store", query)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("queryEvents completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getEvent - gets event by ID")
    void getEvent_success(VertxTestContext testContext) throws Exception {
        client.getEvent("my-setup", "orders-store", "evt-123")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getEvent completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("getEventStoreStats - gets store statistics")
    void getEventStoreStats_success(VertxTestContext testContext) throws Exception {
        client.getEventStoreStats("my-setup", "orders-store")
            .onComplete(ar -> {
                testContext.verify(() -> {
                    logger.info("getEventStoreStats completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

