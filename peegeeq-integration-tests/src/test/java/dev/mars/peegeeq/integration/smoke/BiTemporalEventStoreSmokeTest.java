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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BiTemporal Event Store E2E Smoke Tests.
 * 
 * Verifies complete event flow through bi-temporal event store:
 * Client -> REST API -> Runtime -> BiTemporal Store -> PostgreSQL
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("BiTemporal Event Store Smoke Tests")
class BiTemporalEventStoreSmokeTest extends SmokeTestBase {

    private static final String EVENT_STORE_NAME = "smoke_event_store";

    @Test
    @DisplayName("Should create database setup with event store")
    void testCreateEventStoreSetup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Create event store setup response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should append event with bi-temporal dimensions")
    void testAppendEvent(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);
                
                String aggregateId = "order-" + System.currentTimeMillis();
                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", aggregateId)
                    .put("eventType", "OrderCreated")
                    .put("payload", new JsonObject()
                        .put("orderId", aggregateId)
                        .put("customerId", "CUST-12345")
                        .put("totalAmount", 199.99)
                        .put("items", new JsonArray()
                            .add(new JsonObject()
                                .put("productId", "PROD-001")
                                .put("quantity", 2)
                                .put("price", 99.99))))
                    .put("validTime", Instant.now().toString())
                    .put("correlationId", "corr-" + System.currentTimeMillis());

                return webClient.post("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Append event response: {} - {}", statusCode, response.bodyAsString());

                    // Event store append endpoint must return 200 or 201 for success
                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    logger.info("Event appended successfully");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should query events by event type")
    void testQueryEventsByType(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                // First append an event
                String aggregateId = "order-" + System.currentTimeMillis();
                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", aggregateId)
                    .put("eventType", "OrderCreated")
                    .put("payload", new JsonObject().put("orderId", aggregateId))
                    .put("validTime", Instant.now().toString());

                return webClient.post("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                // Verify append succeeded before querying
                int appendStatus = appendResponse.statusCode();
                logger.info("Append response before query: {} - {}", appendStatus, appendResponse.bodyAsString());
                assertTrue(appendStatus == 200 || appendStatus == 201,
                    "Append must succeed before query, got " + appendStatus);

                // Then query events
                return webClient.get("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events?eventType=OrderCreated")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Query events response: {} - {}", statusCode, response.bodyAsString());

                    // Query must return 200 with events
                    assertEquals(200, statusCode, "Expected 200, got " + statusCode);

                    logger.info("Events queried successfully");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    private JsonObject createEventStoreSetupRequest(String setupId, String eventStoreName) {
        return new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", getPostgresHost())
                .put("port", getPostgresPort())
                .put("databaseName", "smoke_es_" + System.currentTimeMillis())
                .put("username", getPostgresUsername())
                .put("password", getPostgresPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray()
                .add(new JsonObject()
                    .put("eventStoreName", eventStoreName)
                    .put("tableName", eventStoreName.replace("-", "_"))
                    .put("biTemporalEnabled", true)
                    .put("retentionDays", 365)));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
            .send()
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    logger.info("Setup deleted: {}", setupId);
                } else {
                    logger.warn("Failed to delete setup: {}", setupId, ar.cause());
                }
            });
    }
}


