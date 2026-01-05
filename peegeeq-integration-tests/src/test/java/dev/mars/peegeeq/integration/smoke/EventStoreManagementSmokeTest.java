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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event Store Management API Smoke Tests.
 *
 * Verifies event store CRUD operations through both Management API and Standard REST API:
 * - Management API: POST/GET/DELETE /api/v1/management/event-stores
 * - Standard REST API: POST /api/v1/setups/{setupId}/eventstores
 * - Standard REST API: DELETE /api/v1/eventstores/{setupId}/{eventStoreName}
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Event Store Management API Smoke Tests")
class EventStoreManagementSmokeTest extends SmokeTestBase {

    private static final String EVENT_STORE_NAME = "mgmt_test_store";

    @Test
    @DisplayName("Should create event store via Management API with biTemporalEnabled")
    void testCreateEventStoreViaManagementApi(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // First create database setup
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", getPostgresHost())
                .put("port", getPostgresPort())
                .put("databaseName", "mgmt_es_" + System.currentTimeMillis())
                .put("username", getPostgresUsername())
                .put("password", getPostgresPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray());

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // Create event store via Management API with biTemporalEnabled
                JsonObject eventStoreRequest = new JsonObject()
                    .put("name", EVENT_STORE_NAME)
                    .put("setup", setupId)
                    .put("biTemporalEnabled", true)
                    .put("retentionDays", 365);

                return webClient.post("/api/v1/management/event-stores")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventStoreRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Create event store response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(201, statusCode, "Expected 201 Created");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertEquals(EVENT_STORE_NAME, responseBody.getString("eventStoreName"));
                    assertEquals(setupId, responseBody.getString("setupId"));
                    assertTrue(responseBody.getBoolean("biTemporalEnabled"),
                        "biTemporalEnabled should be true");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should list event stores via Management API")
    void testListEventStoresViaManagementApi(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // Create setup with event store
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup with event store created: {}", setupId);

                // List event stores via Management API
                return webClient.get("/api/v1/management/event-stores")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");

                    JsonArray eventStores = responseBody.getJsonArray("eventStores");
                    assertNotNull(eventStores, "Event stores array should not be null");
                    assertTrue(eventStores.size() > 0, "Should have at least one event store");

                    // Find our event store
                    boolean found = eventStores.stream()
                        .filter(obj -> obj instanceof JsonObject)
                        .map(obj -> (JsonObject) obj)
                        .anyMatch(store -> EVENT_STORE_NAME.equals(store.getString("name"))
                            && setupId.equals(store.getString("setup")));

                    assertTrue(found, "Should find created event store in list");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should delete event store via Management API (composite ID)")
    void testDeleteEventStoreViaManagementApi(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // Create setup with event store
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup with event store created: {}", setupId);

                // Delete via Management API using composite ID
                String storeId = setupId + "-" + EVENT_STORE_NAME;
                return webClient.delete("/api/v1/management/event-stores/" + storeId)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "DELETE should return 200 OK. Response: " + response.bodyAsString());

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody);
                    assertTrue(responseBody.getString("message").contains("deleted successfully"));
                });

                cleanupSetup(setupId);
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should delete event store via Standard REST API (separate params)")
    void testDeleteEventStoreViaStandardRestApi(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // Create setup with event store
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup with event store created: {}", setupId);

                // Delete via Standard REST API using separate parameters
                return webClient.delete("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "DELETE should return 200 OK. Response: " + response.bodyAsString());

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody);
                    assertTrue(responseBody.getString("message").contains("deleted successfully"));
                    assertEquals(setupId, responseBody.getString("setupId"));
                    assertEquals(EVENT_STORE_NAME, responseBody.getString("storeName"));
                });

                cleanupSetup(setupId);
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should create event store via Standard REST API")
    void testCreateEventStoreViaStandardRestApi(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // First create database setup without event stores
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", getPostgresHost())
                .put("port", getPostgresPort())
                .put("databaseName", "std_es_" + System.currentTimeMillis())
                .put("username", getPostgresUsername())
                .put("password", getPostgresPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray());

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // Add event store via Standard REST API
                JsonObject eventStoreRequest = new JsonObject()
                    .put("eventStoreName", EVENT_STORE_NAME)
                    .put("tableName", EVENT_STORE_NAME + "_events")
                    .put("biTemporalEnabled", true)
                    .put("retentionDays", 365);

                return webClient.post("/api/v1/setups/" + setupId + "/eventstores")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventStoreRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Add event store response: {} - {}", statusCode, response.bodyAsString());

                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should fail to delete non-existent event store")
    void testDeleteNonExistentEventStore(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // Create setup without event stores
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", getPostgresHost())
                .put("port", getPostgresPort())
                .put("databaseName", "test_" + System.currentTimeMillis())
                .put("username", getPostgresUsername())
                .put("password", getPostgresPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray());

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // Try to delete non-existent event store
                return webClient.delete("/api/v1/eventstores/" + setupId + "/nonexistent_store")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "DELETE of non-existent event store should return 404");

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
                .put("databaseName", "mgmt_es_" + System.currentTimeMillis())
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


