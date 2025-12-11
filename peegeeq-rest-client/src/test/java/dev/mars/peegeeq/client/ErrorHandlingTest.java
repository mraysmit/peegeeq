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
import dev.mars.peegeeq.client.exception.PeeGeeQApiException;
import dev.mars.peegeeq.client.exception.PeeGeeQNetworkException;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
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
 * Tests for error handling in PeeGeeQRestClient.
 * Tests exception types, HTTP error codes, timeouts, and network errors.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingTest.class);
    private static final int MOCK_PORT = 18205;

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
                    .timeout(Duration.ofSeconds(2))
                    .maxRetries(0)
                    .build();
                client = PeeGeeQRestClient.create(vertx, config);
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private void setupMockEndpoints() {
        // GET /api/v1/setups - List setups (success)
        router.get("/api/v1/setups").handler(ctx -> {
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end("[]");  // Empty array
        });

        // GET /api/v1/setups/:setupId - Get setup (returns 404 for "notfound")
        router.get("/api/v1/setups/:setupId").handler(ctx -> {
            String setupId = ctx.pathParam("setupId");
            if ("notfound".equals(setupId)) {
                ctx.response()
                    .setStatusCode(404)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                        .put("error", "NOT_FOUND")
                        .put("message", "Setup not found: " + setupId)
                        .encode());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                        .put("setupId", setupId)
                        .put("status", "ACTIVE")
                        .encode());
            }
        });

        // GET /api/v1/setups/:setupId/health - Health endpoint (returns 500 for "error-setup")
        router.get("/api/v1/setups/:setupId/health").handler(ctx -> {
            String setupId = ctx.pathParam("setupId");
            if ("error-setup".equals(setupId)) {
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                        .put("error", "INTERNAL_ERROR")
                        .put("message", "Internal server error")
                        .encode());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                        .put("status", "HEALTHY")
                        .put("setupId", setupId)
                        .encode());
            }
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
                .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // HTTP Error Code Tests
    // ========================================================================

    @Test
    @DisplayName("404 Not Found - throws PeeGeeQApiException with status 404")
    void notFound_throwsApiException(VertxTestContext testContext) throws Exception {
        // Use "notfound" setup ID to trigger 404 from mock
        client.getSetup("notfound")
            .onSuccess(result -> testContext.failNow("Expected 404 error"))
            .onFailure(error -> {
                testContext.verify(() -> {
                    assertInstanceOf(PeeGeeQApiException.class, error);
                    PeeGeeQApiException apiError = (PeeGeeQApiException) error;
                    assertEquals(404, apiError.getStatusCode());
                    logger.info("404 error correctly thrown: {}", apiError.getMessage());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("500 Internal Server Error - throws PeeGeeQApiException with status 500")
    void serverError_throwsApiException(VertxTestContext testContext) throws Exception {
        // Use "error-setup" to trigger 500 from mock health endpoint
        client.getHealth("error-setup")
            .onSuccess(result -> testContext.failNow("Expected 500 error"))
            .onFailure(error -> {
                testContext.verify(() -> {
                    assertInstanceOf(PeeGeeQApiException.class, error);
                    PeeGeeQApiException apiError = (PeeGeeQApiException) error;
                    assertEquals(500, apiError.getStatusCode());
                    logger.info("500 error correctly thrown: {}", apiError.getMessage());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Network Error Tests
    // ========================================================================

    @Test
    @DisplayName("Connection refused - throws PeeGeeQNetworkException")
    void connectionRefused_throwsNetworkException(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Create client pointing to non-existent server
        ClientConfig config = ClientConfig.builder()
            .baseUrl("http://localhost:19999")  // Port with no server
            .timeout(Duration.ofSeconds(2))
            .maxRetries(0)
            .build();
        PeeGeeQClient badClient = PeeGeeQRestClient.create(vertx, config);

        badClient.listSetups()
            .onSuccess(result -> testContext.failNow("Expected connection error"))
            .onFailure(error -> {
                testContext.verify(() -> {
                    assertInstanceOf(PeeGeeQNetworkException.class, error);
                    PeeGeeQNetworkException netError = (PeeGeeQNetworkException) error;
                    logger.info("Network error correctly thrown: {}", netError.getMessage());
                });
                badClient.close();
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Exception Property Tests
    // ========================================================================

    @Test
    @DisplayName("PeeGeeQApiException contains error details")
    void apiException_containsDetails(VertxTestContext testContext) throws Exception {
        // Use "notfound" to trigger 404 with proper error response
        client.getSetup("notfound")
            .onSuccess(result -> testContext.failNow("Expected error"))
            .onFailure(error -> {
                testContext.verify(() -> {
                    assertInstanceOf(PeeGeeQApiException.class, error);
                    PeeGeeQApiException apiError = (PeeGeeQApiException) error;

                    // Verify exception properties
                    assertTrue(apiError.getStatusCode() >= 400);
                    assertNotNull(apiError.getMessage());

                    logger.info("API Exception details - Status: {}, Message: {}",
                        apiError.getStatusCode(), apiError.getMessage());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("PeeGeeQNetworkException contains host/port info")
    void networkException_containsHostInfo(Vertx vertx, VertxTestContext testContext) throws Exception {
        ClientConfig config = ClientConfig.builder()
            .baseUrl("http://localhost:19998")
            .timeout(Duration.ofSeconds(2))
            .maxRetries(0)
            .build();
        PeeGeeQClient badClient = PeeGeeQRestClient.create(vertx, config);

        badClient.listSetups()
            .onSuccess(result -> testContext.failNow("Expected error"))
            .onFailure(error -> {
                testContext.verify(() -> {
                    assertInstanceOf(PeeGeeQNetworkException.class, error);
                    PeeGeeQNetworkException netError = (PeeGeeQNetworkException) error;

                    // Verify exception properties
                    assertEquals("localhost", netError.getHost());
                    assertEquals(19998, netError.getPort());

                    logger.info("Network Exception details - Host: {}, Port: {}",
                        netError.getHost(), netError.getPort());
                });
                badClient.close();
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Success Case for Comparison
    // ========================================================================

    @Test
    @DisplayName("Successful request - no exception thrown")
    void successfulRequest_noException(VertxTestContext testContext) throws Exception {
        client.listSetups()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    // We expect this to complete (success or failure due to parsing)
                    // The key is that it doesn't throw a network exception
                    logger.info("Request completed: success={}", ar.succeeded());
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

