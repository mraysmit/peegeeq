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

package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for enhanced Event Store querying functionality.
 *
 * Uses TestContainers and real PeeGeeQRuntime to test actual REST endpoints.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStoreEnhancementTest {

    private static final Logger logger = LoggerFactory.getLogger(EventStoreEnhancementTest.class);
    private static final int TEST_PORT = 18096;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_eventstore_enhancement_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private WebClient client;
    private String deploymentId;
    private String testSetupId;
    private String testDbName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Starting Event Store Enhancement Integration Test ===");

        client = WebClient.create(vertx);
        testSetupId = "eventstore-enhance-" + System.currentTimeMillis();
        testDbName = "es_enhance_" + System.currentTimeMillis();

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {}", TEST_PORT);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down Event Store Enhancement Test ===");

        if (client != null) {
            client.close();
        }
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> {
                    logger.info("Test cleanup completed");
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    void testCreateEventStoreSetup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 1: Create Event Store Setup ===");

        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", testDbName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray()
                .add(new JsonObject()
                    .put("eventStoreName", "order_events")
                    .put("tableName", "order_events")
                    .put("biTemporalEnabled", true)
                    .put("notificationPrefix", "event_")
                    .put("partitioningEnabled", false)))
            .put("additionalProperties", new JsonObject());

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(201, response.statusCode(), "Setup should return 201 Created");
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("ACTIVE", body.getString("status"));
                logger.info("Event store setup created successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testStoreEvent(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: Store Event ===");

        JsonObject eventRequest = new JsonObject()
            .put("eventType", "OrderCreated")
            .put("eventData", new JsonObject()
                .put("orderId", "ORD-12345")
                .put("customerId", "CUST-67890")
                .put("amount", 199.99)
                .put("currency", "USD"))
            .put("correlationId", "corr-" + System.currentTimeMillis())
            .put("validFrom", Instant.now().toString())
            .put("metadata", new JsonObject()
                .put("source", "integration-test")
                .put("version", "2.0"));

        client.post(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order_events/events")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(eventRequest)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(201, response.statusCode(), "Event store should return 201 Created");
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("Event stored successfully", body.getString("message"));
                assertNotNull(body.getString("eventId"), "Event ID should be returned");
                logger.info("Event stored: {}", body.getString("eventId"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testQueryEvents(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: Query Events ===");

        client.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order_events/events")
            .addQueryParam("eventType", "OrderCreated")
            .addQueryParam("limit", "10")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Query should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");

                // Verify response structure
                assertTrue(body.containsKey("events"), "events array should be present");
                assertTrue(body.containsKey("eventCount") || body.containsKey("totalCount"),
                    "event count should be present");

                JsonArray events = body.getJsonArray("events");
                assertNotNull(events, "events array should not be null");
                assertTrue(events.size() >= 1, "Should have at least one event");

                // Verify event structure
                JsonObject event = events.getJsonObject(0);
                assertNotNull(event.getString("eventType"), "eventType should be present");
                assertTrue(event.containsKey("eventData") || event.containsKey("payload"),
                    "event data should be present");

                logger.info("Query returned {} events", events.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    void testQueryEventsWithTimeRange(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: Query Events with Time Range ===");

        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);

        client.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order_events/events")
            .addQueryParam("fromTime", oneHourAgo.toString())
            .addQueryParam("toTime", now.toString())
            .addQueryParam("limit", "50")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Time range query should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");
                assertTrue(body.containsKey("events"), "events array should be present");

                logger.info("Time range query response: {}", body.encode());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    void testGetEventStoreStats(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: Get Event Store Statistics ===");

        client.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order_events/stats")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Stats should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");

                // Verify stats structure
                assertTrue(body.containsKey("stats") || body.containsKey("totalEvents"),
                    "stats should be present");

                logger.info("Event store stats: {}", body.encode());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    void testStoreMultipleEventsAndQuery(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: Store Multiple Events and Query ===");

        // Store multiple events with different types
        JsonObject event1 = new JsonObject()
            .put("eventType", "OrderUpdated")
            .put("eventData", new JsonObject()
                .put("orderId", "ORD-12345")
                .put("status", "CONFIRMED"))
            .put("validFrom", Instant.now().toString());

        JsonObject event2 = new JsonObject()
            .put("eventType", "OrderShipped")
            .put("eventData", new JsonObject()
                .put("orderId", "ORD-12345")
                .put("trackingNumber", "TRACK-ABC123"))
            .put("validFrom", Instant.now().toString());

        // Store first event
        client.post(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order_events/events")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(event1)
            .compose(r1 -> {
                testContext.verify(() -> assertEquals(201, r1.statusCode(), "Event store should return 201 Created"));
                // Store second event
                return client.post(TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/order_events/events")
                    .putHeader("content-type", "application/json")
                    .timeout(10000)
                    .sendJsonObject(event2);
            })
            .compose(r2 -> {
                testContext.verify(() -> assertEquals(201, r2.statusCode(), "Event store should return 201 Created"));
                // Query all events
                return client.get(TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/order_events/events")
                    .addQueryParam("limit", "100")
                    .timeout(10000)
                    .send();
            })
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());

                JsonObject body = response.bodyAsJsonObject();
                JsonArray events = body.getJsonArray("events");

                assertTrue(events.size() >= 3, "Should have at least 3 events now");
                logger.info("Total events after multiple stores: {}", events.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
