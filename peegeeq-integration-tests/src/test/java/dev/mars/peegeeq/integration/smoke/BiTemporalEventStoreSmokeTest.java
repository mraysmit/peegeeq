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
    @DisplayName("Should create database setup with event store and receive 200 or 201")
    void testCreateEventStoreSetup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        logger.info("=== TEST: Create event store setup (setupId={}, eventStore={}) ===", setupId, EVENT_STORE_NAME);
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    String body = response.bodyAsString();
                    logger.info("Create event store setup response: status={}, body={}", statusCode, body);

                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode + ". Body: " + body);

                    logger.info("=== TEST PASSED: testCreateEventStoreSetup (setupId={}) ===", setupId);
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should append event with bi-temporal dimensions and receive eventId in response")
    void testAppendEvent(VertxTestContext testContext) {
        String setupId = generateSetupId();
        logger.info("=== TEST: Append bi-temporal event (setupId={}, eventStore={}) ===", setupId, EVENT_STORE_NAME);
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: setupId={} (status={})", setupId, setupResponse.statusCode());

                String aggregateId = "order-" + System.currentTimeMillis();
                logger.info("Appending OrderCreated event: aggregateId={}, totalAmount=199.99", aggregateId);
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
                    String rawBody = response.bodyAsString();
                    logger.info("Append event response: status={}, body={}", statusCode, rawBody);

                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode + ". Body: " + rawBody);

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertNotNull(body.getString("eventId"),
                        "Response must include eventId. Body: " + rawBody);

                    logger.info("Event appended successfully: eventId={}", body.getString("eventId"));
                    logger.info("=== TEST PASSED: testAppendEvent (setupId={}) ===", setupId);
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should query events by event type and return at least one matching event")
    void testQueryEventsByType(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String aggregateId = "order-" + System.currentTimeMillis();
        logger.info("=== TEST: Query events by event type (setupId={}, aggregateId={}, eventStore={}) ===",
            setupId, aggregateId, EVENT_STORE_NAME);
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: setupId={} (status={})", setupId, setupResponse.statusCode());

                logger.info("Appending OrderCreated event to query back: aggregateId={}", aggregateId);
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
                int appendStatus = appendResponse.statusCode();
                String appendBody = appendResponse.bodyAsString();
                logger.info("Event appended before query: status={}, body={}", appendStatus, appendBody);
                assertTrue(appendStatus == 200 || appendStatus == 201,
                    "Append must succeed before query, got " + appendStatus + ". Body: " + appendBody);

                logger.info("Querying events with eventType=OrderCreated from store={}", EVENT_STORE_NAME);
                return webClient.get("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events?eventType=OrderCreated")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    String rawBody = response.bodyAsString();
                    logger.info("Query events response: status={}, body={}", statusCode, rawBody);

                    assertEquals(200, statusCode, "Expected 200, got " + statusCode + ". Body: " + rawBody);

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must not be null");
                    JsonArray events = body.getJsonArray("events");
                    assertNotNull(events, "Response must include an events array. Body: " + rawBody);
                    assertTrue(events.size() >= 1,
                        "Expected at least 1 OrderCreated event, got " + events.size() + ". Body: " + rawBody);

                    logger.info("Query returned {} event(s) matching eventType=OrderCreated", events.size());
                    logger.info("=== TEST PASSED: testQueryEventsByType (setupId={}) ===", setupId);
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    /**
     * Verifies that a bi-temporal correction can be appended to an existing event
     * and that the version lineage is correctly reflected in the API response.
     *
     * <h3>What is being tested</h3>
     * The correction pipeline:
     * <ul>
     *   <li>The original event is stored and receives a stable {@code eventId}.</li>
     *   <li>A correction is posted to
     *       {@code POST /api/v1/eventstores/{setupId}/{storeName}/events/{eventId}/corrections}.</li>
     *   <li>The response carries {@code correctionEventId}, {@code originalEventId},
     *       a {@code version} of at least 2, and the round-tripped {@code correctionReason}.</li>
     * </ul>
     *
     * <h3>Coverage gap addressed</h3>
     * Corrections were previously tested only inside the {@code peegeeq-rest} module's own
     * integration test ({@code EventStoreIntegrationTest}), which uses an in-process setup.
     * This test exercises the same path through the fully deployed REST server against a real
     * PostgreSQL Testcontainer, matching the smoke-test tier contract.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Create a PeeGeeQ event store setup via the REST API.</li>
     *   <li>Append an original {@code PriceSet} event with price 89.99; capture {@code eventId}.</li>
     *   <li>POST a correction for that event with price 99.99 and a {@code correctionReason}.</li>
     *   <li>Assert the correction response returns 201 with {@code correctionEventId},
     *       {@code originalEventId}, {@code version >= 2}, and the original reason text.</li>
     * </ol>
     */
    @Test
    @DisplayName("Should append correction to event and verify version lineage")
    void testAppendCorrectionToEvent(VertxTestContext testContext) {
        logger.info("=== TEST: Append correction to bi-temporal event (setupId will be logged below) ===");
        String setupId = generateSetupId();
        JsonObject setupRequest = createEventStoreSetupRequest(setupId, EVENT_STORE_NAME);

        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Event store setup created: {} (status={})", setupId, setupResponse.statusCode());
                String aggregateId = "order-corr-" + System.currentTimeMillis();
                logger.info("Appending original PriceSet event (aggregateId={}, price=89.99)", aggregateId);
                JsonObject originalEvent = new JsonObject()
                    .put("aggregateId", aggregateId)
                    .put("eventType", "PriceSet")
                    .put("eventData", new JsonObject().put("price", 89.99))
                    .put("validFrom", Instant.now().toString())
                    .put("correlationId", "corr-" + setupId);

                return webClient.post("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(originalEvent);
            })
            .compose(appendResponse -> {
                int status = appendResponse.statusCode();
                logger.info("Original event appended: {} - {}", status, appendResponse.bodyAsString());
                if (status != 200 && status != 201) {
                    return Future.failedFuture(new AssertionError(
                        "Expected 200/201 for original event append, got " + status));
                }

                String eventId = appendResponse.bodyAsJsonObject().getString("eventId");
                if (eventId == null) {
                    return Future.failedFuture(new AssertionError(
                        "Original event append response missing eventId"));
                }
                logger.info("Original event stored with eventId={}, posting correction (price=99.99)", eventId);
                JsonObject correctionRequest = new JsonObject()
                    .put("eventData", new JsonObject().put("price", 99.99))
                    .put("correctionReason", "Original price was incorrect")
                    .put("validFrom", Instant.now().toString())
                    .put("correlationId", "corr-" + setupId);

                return webClient.post("/api/v1/eventstores/" + setupId + "/" + EVENT_STORE_NAME
                        + "/events/" + eventId + "/corrections")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(correctionRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Correction response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(201, statusCode, "Expected 201 for correction, got " + statusCode);

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getString("correctionEventId"),
                        "Correction response must include correctionEventId");
                    assertNotNull(body.getString("originalEventId"),
                        "Correction response must include originalEventId");
                    assertTrue(body.getInteger("version") >= 2,
                        "Correction version must be >= 2, got " + body.getInteger("version"));
                    assertEquals("Original price was incorrect", body.getString("correctionReason"),
                        "correctionReason must match");

                    logger.info("Correction verified: correctionEventId={}, originalEventId={}, version={}",
                        body.getString("correctionEventId"), body.getString("originalEventId"),
                        body.getInteger("version"));
                    logger.info("=== TEST PASSED: testAppendCorrectionToEvent ===");
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
            .onFailure(err -> logger.warn("Failed to delete setup: {}", setupId, err));
    }
}


