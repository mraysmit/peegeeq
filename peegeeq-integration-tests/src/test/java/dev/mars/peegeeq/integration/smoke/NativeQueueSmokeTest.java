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
package dev.mars.peegeeq.integration.smoke;

import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native Queue E2E Smoke Tests.
 * 
 * Verifies complete message flow through native queue pattern:
 * JS/Java Client -> REST API -> Runtime -> Native Queue -> PostgreSQL
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Native Queue Smoke Tests")
class NativeQueueSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "smoke_test_queue";

    @Test
    @DisplayName("Should create database setup with native queue")
    void testCreateDatabaseSetup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Create setup response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertTrue(statusCode == 200 || statusCode == 201, 
                        "Expected 200 or 201, got " + statusCode);
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    
                    // Cleanup
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should send message and receive confirmation")
    void testSendMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                JsonObject messagePayload = new JsonObject()
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-12345")
                        .put("customerId", "CUST-67890")
                        .put("amount", 99.99)
                        .put("timestamp", System.currentTimeMillis()))
                    .put("priority", 5)
                    .put("headers", new JsonObject()
                        .put("source", "smoke-test")
                        .put("version", "1.0"));

                return webClient.post("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(messagePayload);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Send message response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertNotNull(body.getString("messageId"), "Should have messageId");
                    assertEquals(QUEUE_NAME, body.getString("queueName"), "Queue name should match");
                    assertEquals(setupId, body.getString("setupId"), "Setup ID should match");
                    
                    logger.info("Message sent successfully: {}", body.getString("messageId"));
                    
                    // Cleanup
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should propagate correlation ID through all layers")
    void testCorrelationIdPropagation(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String correlationId = "corr-" + System.currentTimeMillis();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                JsonObject messagePayload = new JsonObject()
                    .put("payload", new JsonObject().put("test", "correlation-test"))
                    .put("correlationId", correlationId);

                return webClient.post("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(messagePayload);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(correlationId, body.getString("correlationId"),
                        "Correlation ID should be propagated");
                    
                    logger.info("Correlation ID propagated: {}", correlationId);
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    /**
     * Verifies that a published message is reflected in the queue statistics endpoint.
     *
     * <h3>What is being tested</h3>
     * {@code GET /api/v1/queues/{setupId}/{queueName}/stats} must return live statistics
     * sourced from the database, not cached or stale counters. After publishing exactly
     * one message the {@code totalMessages} field must be at least 1, confirming that
     * the stats endpoint queries the correct setup's database and serialises the result
     * correctly.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Create a PeeGeeQ native queue setup via the REST API.</li>
     *   <li>Publish one message with a unique {@code correlationId}.</li>
     *   <li>Immediately call the stats endpoint and assert:
     *     <ul>
     *       <li>HTTP 200</li>
     *       <li>{@code queueName} and {@code setupId} fields round-trip correctly</li>
     *       <li>{@code totalMessages >= 1}</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>Coverage gap addressed</h3>
     * The existing smoke tests ({@code testSendMessage}, {@code testCorrelationIdPropagation})
     * verify only the publish response. None of them verify that the message is actually
     * recorded in the queue layer and visible through the stats API. This test closes
     * that gap.
     */
    @Test
    @DisplayName("Published message appears in queue stats")
    void testPublishedMessageAppearsInQueueStats(VertxTestContext testContext) {
        logger.info("=== TEST: Published message appears in queue stats (setupId will be logged below) ===");
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {} (status={})", setupId, setupResponse.statusCode());

                JsonObject messagePayload = new JsonObject()
                    .put("payload", new JsonObject().put("data", "stats-test"))
                    .put("correlationId", "stats-" + setupId);

                logger.info("Publishing message to queue {} (correlationId=stats-{})", QUEUE_NAME, setupId);
                return webClient.post("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(messagePayload);
            })
            .compose(publishResponse -> {
                int publishStatus = publishResponse.statusCode();
                logger.info("Publish response: {} - {}", publishStatus, publishResponse.bodyAsString());
                if (publishStatus != 200 && publishStatus != 201) {
                    return Future.failedFuture(new AssertionError(
                        "Publish must succeed before checking stats, got " + publishStatus));
                }

                return webClient.get("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/stats")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Queue stats response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Stats endpoint must return 200");

                    JsonObject stats = response.bodyAsJsonObject();
                    assertNotNull(stats, "Stats body must not be null");
                    assertEquals(QUEUE_NAME, stats.getString("queueName"), "queueName must match");
                    assertEquals(setupId, stats.getString("setupId"), "setupId must match");

                    long totalMessages = stats.getLong("totalMessages", 0L);
                    assertTrue(totalMessages >= 1,
                        "totalMessages must be >= 1 after publishing, got " + totalMessages);

                    logger.info("Queue stats verified: totalMessages={}, pendingMessages={}",
                        totalMessages, stats.getLong("pendingMessages", 0L));
                    logger.info("=== TEST PASSED: testPublishedMessageAppearsInQueueStats ===");
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to delete setup: {}", setupId, err));
    }
}


