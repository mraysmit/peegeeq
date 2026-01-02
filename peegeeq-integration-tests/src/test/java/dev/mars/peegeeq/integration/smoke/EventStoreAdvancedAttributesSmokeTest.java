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
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event Store Advanced Attributes Smoke Tests.
 *
 * Verifies that advanced event attributes are properly stored and retrieved:
 * - aggregateId: Groups related events together
 * - correlationId: Tracks event flow across services
 * - causationId: What caused this event
 * - validTime: Business time (when event actually happened)
 * - metadata/headers: Custom metadata in JSON format
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Event Store Advanced Attributes Smoke Tests")
class EventStoreAdvancedAttributesSmokeTest extends SmokeTestBase {

    private static final String EVENT_STORE_NAME = "advanced_attrs_store";

    @Test
    @DisplayName("Should store and retrieve event with aggregateId")
    void testEventWithAggregateId(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);

                String aggregateId = "order-aggregate-12345";
                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", aggregateId)
                    .put("eventType", "OrderCreated")
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-001")
                        .put("amount", 199.99));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                assertTrue(appendResponse.statusCode() == 200 || appendResponse.statusCode() == 201,
                    "Append must succeed, got " + appendResponse.statusCode());

                // Query events and verify aggregateId is returned
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");

                    JsonArray events = body.getJsonArray("events");
                    assertNotNull(events, "Events array should not be null");
                    assertTrue(events.size() > 0, "Should have at least one event");

                    JsonObject event = events.getJsonObject(0);
                    assertEquals("order-aggregate-12345", event.getString("aggregateId"),
                        "aggregateId should be preserved");
                    assertEquals("OrderCreated", event.getString("eventType"));

                    logger.info("✅ aggregateId correctly stored and retrieved: {}",
                        event.getString("aggregateId"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should store and retrieve event with correlationId and causationId")
    void testEventWithCorrelationAndCausation(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);

                String correlationId = "corr-workflow-567";
                String causationId = "evt-user-action-123";

                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", "order-" + System.currentTimeMillis())
                    .put("eventType", "OrderWithEventSourcing")
                    .put("correlationId", correlationId)
                    .put("causationId", causationId)
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-ES-001")
                        .put("amount", 499.99));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                assertTrue(appendResponse.statusCode() == 200 || appendResponse.statusCode() == 201,
                    "Append must succeed, got " + appendResponse.statusCode());

                // Query events and verify correlation/causation IDs are returned
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray events = body.getJsonArray("events");
                    assertTrue(events.size() > 0);

                    JsonObject event = events.getJsonObject(0);
                    assertEquals("corr-workflow-567", event.getString("correlationId"),
                        "correlationId should be preserved");
                    assertEquals("evt-user-action-123", event.getString("causationId"),
                        "causationId should be preserved");
                    assertEquals("OrderWithEventSourcing", event.getString("eventType"));

                    logger.info("✅ correlationId correctly stored and retrieved: {}",
                        event.getString("correlationId"));
                    logger.info("✅ causationId correctly stored and retrieved: {}",
                        event.getString("causationId"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should store and retrieve event with validTime (business time)")
    void testEventWithValidTime(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);

                // Use specific business time (past date for testing)
                String validTime = Instant.now().minus(7, ChronoUnit.DAYS).toString();

                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", "order-temporal-001")
                    .put("eventType", "OrderCreatedWithValidTime")
                    .put("validTime", validTime)
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-TEMPORAL-001")
                        .put("amount", 299.99));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                assertTrue(appendResponse.statusCode() == 200 || appendResponse.statusCode() == 201,
                    "Append must succeed, got " + appendResponse.statusCode());

                // Query events and verify validTime is returned
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray events = body.getJsonArray("events");
                    assertTrue(events.size() > 0);

                    JsonObject event = events.getJsonObject(0);
                    assertNotNull(event.getString("validTime"),
                        "validTime should be present");
                    // Note: validFrom might be used instead of validTime in bi-temporal stores
                    assertTrue(event.containsKey("validTime") || event.containsKey("validFrom"),
                        "Either validTime or validFrom should be present");

                    logger.info("✅ validTime correctly stored and retrieved: {}",
                        event.getString("validTime") != null ?
                            event.getString("validTime") : event.getString("validFrom"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should store and retrieve event with metadata/headers")
    void testEventWithMetadata(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);

                JsonObject metadata = new JsonObject()
                    .put("userId", "user-123")
                    .put("source", "web-ui")
                    .put("ipAddress", "192.168.1.1")
                    .put("userAgent", "Chrome/120.0");

                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", "order-metadata-001")
                    .put("eventType", "OrderWithMetadata")
                    .put("metadata", metadata)
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-META-001")
                        .put("amount", 199.99));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                assertTrue(appendResponse.statusCode() == 200 || appendResponse.statusCode() == 201,
                    "Append must succeed, got " + appendResponse.statusCode());

                // Query events and verify metadata is returned
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray events = body.getJsonArray("events");
                    assertTrue(events.size() > 0);

                    JsonObject event = events.getJsonObject(0);
                    JsonObject returnedMetadata = event.getJsonObject("metadata");
                    if (returnedMetadata == null) {
                        returnedMetadata = event.getJsonObject("headers");
                    }

                    assertNotNull(returnedMetadata,
                        "metadata/headers should be present");
                    assertEquals("user-123", returnedMetadata.getString("userId"),
                        "metadata.userId should be preserved");
                    assertEquals("web-ui", returnedMetadata.getString("source"),
                        "metadata.source should be preserved");

                    logger.info("✅ metadata correctly stored and retrieved: {}",
                        returnedMetadata.encodePrettily());

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should store and retrieve event with ALL advanced attributes combined")
    void testEventWithAllAdvancedAttributes(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {}", setupId);

                String aggregateId = "complete-order-aggregate-999";
                String correlationId = "corr-complete-workflow-888";
                String causationId = "evt-complete-action-777";
                String validTime = Instant.now().minus(1, ChronoUnit.DAYS).toString();

                JsonObject metadata = new JsonObject()
                    .put("userId", "user-complete-456")
                    .put("source", "integration-test")
                    .put("testRun", "advanced-options-test");

                JsonObject eventPayload = new JsonObject()
                    .put("aggregateId", aggregateId)
                    .put("correlationId", correlationId)
                    .put("causationId", causationId)
                    .put("validTime", validTime)
                    .put("metadata", metadata)
                    .put("eventType", "CompleteEventWithAllOptions")
                    .put("payload", new JsonObject()
                        .put("orderId", "ORDER-COMPLETE-001")
                        .put("amount", 999.99)
                        .put("items", new JsonArray()
                            .add(new JsonObject()
                                .put("sku", "ITEM-001")
                                .put("quantity", 3))));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(eventPayload);
            })
            .compose(appendResponse -> {
                assertTrue(appendResponse.statusCode() == 200 || appendResponse.statusCode() == 201,
                    "Append must succeed, got " + appendResponse.statusCode());

                // Query events and verify ALL attributes are returned
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray events = body.getJsonArray("events");
                    assertTrue(events.size() > 0);

                    JsonObject event = events.getJsonObject(0);

                    // Verify all advanced attributes
                    assertEquals("complete-order-aggregate-999", event.getString("aggregateId"),
                        "aggregateId should be preserved");
                    assertEquals("corr-complete-workflow-888", event.getString("correlationId"),
                        "correlationId should be preserved");
                    assertEquals("evt-complete-action-777", event.getString("causationId"),
                        "causationId should be preserved");
                    assertNotNull(event.getString("validTime") != null ?
                        event.getString("validTime") : event.getString("validFrom"),
                        "validTime/validFrom should be present");

                    JsonObject returnedMetadata = event.getJsonObject("metadata");
                    if (returnedMetadata == null) {
                        returnedMetadata = event.getJsonObject("headers");
                    }
                    assertNotNull(returnedMetadata, "metadata/headers should be present");
                    assertEquals("user-complete-456", returnedMetadata.getString("userId"));

                    logger.info("✅ ALL advanced attributes correctly stored and retrieved:");
                    logger.info("   - aggregateId: {}", event.getString("aggregateId"));
                    logger.info("   - correlationId: {}", event.getString("correlationId"));
                    logger.info("   - causationId: {}", event.getString("causationId"));
                    logger.info("   - validTime: {}", event.getString("validTime") != null ?
                        event.getString("validTime") : event.getString("validFrom"));
                    logger.info("   - metadata: {}", returnedMetadata.encodePrettily());

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
                .put("databaseName", "advanced_" + System.currentTimeMillis())
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
        webClient.delete(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId)
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

