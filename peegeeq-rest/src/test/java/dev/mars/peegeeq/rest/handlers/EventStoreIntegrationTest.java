package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
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
 * Integration test for EventStore REST API endpoints.
 * 
 * Following coding principles:
 * - Test after every change
 * - Use real TestContainers infrastructure
 * - Work incrementally - start with one simple test
 * - Verify endpoints with actual database setup
 * - Use Vert.x async patterns
 */
@Testcontainers
@Tag("integration")
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventStoreIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(EventStoreIntegrationTest.class);
    private static final int TEST_PORT = 18090;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_eventstore_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private PeeGeeQRestServer restServer;
    private WebClient webClient;
    private String testSetupId;
    private String deploymentId;

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== EVENTSTORE TEST SETUP STARTED ===");
        
        testSetupId = "eventstore_test_" + System.currentTimeMillis();
        logger.info("Test Setup ID: {}", testSetupId);

        // Deploy REST server first
        restServer = new PeeGeeQRestServer(TEST_PORT);
        vertx.deployVerticle(restServer)
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed with ID: {}", deploymentId);

                    webClient = WebClient.create(vertx);

                    // Give server time to fully start
                    vertx.setTimer(1000, timerId -> {
                        // Now create database setup via REST API
                        createDatabaseSetupViaRestApi(testContext);
                    });
                })
                .onFailure(testContext::failNow);
    }

    private void createDatabaseSetupViaRestApi(VertxTestContext testContext) {
        JsonObject setupRequest = new JsonObject()
                .put("setupId", testSetupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "eventstore_test_db_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "public")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new JsonArray())
                .put("eventStores", new JsonArray()
                        .add(new JsonObject()
                                .put("eventStoreName", "test_events")
                                .put("tableName", "test_events")
                                .put("biTemporalEnabled", true)
                                .put("notificationPrefix", "test_events_")))
                .put("additionalProperties", new JsonObject().put("test", "eventstore"));

        logger.info("Creating database setup via REST API: {}", testSetupId);

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(setupRequest)
                .onSuccess(response -> {
                    logger.info("Setup creation response status: {}", response.statusCode());
                    logger.info("Setup creation response body: {}", response.bodyAsString());

                    if (response.statusCode() == 201 || response.statusCode() == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        logger.info("✅ Setup created successfully: {}", body.getString("setupId"));
                        logger.info("=== EVENTSTORE TEST SETUP COMPLETE ===");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new Exception("Failed to create setup: " + response.bodyAsString()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDownAll(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== EVENTSTORE TEST CLEANUP STARTED ===");

        if (webClient != null) {
            webClient.close();
        }

        // Destroy setup via REST API BEFORE undeploying the server
        if (testSetupId != null && deploymentId != null) {
            WebClient client = WebClient.create(vertx);
            client.delete(TEST_PORT, "localhost", "/api/v1/database-setup/" + testSetupId)
                    .timeout(30000)
                    .send()
                    .compose(response -> {
                        client.close();
                        if (response.statusCode() == 200 || response.statusCode() == 204) {
                            logger.info("✅ Cleanup completed");
                        } else {
                            logger.warn("⚠️ Cleanup failed with status: {}", response.statusCode());
                        }
                        return vertx.undeploy(deploymentId);
                    })
                    .onComplete(ar -> {
                        logger.info("=== EVENTSTORE TEST CLEANUP COMPLETE ===");
                        testContext.completeNow();
                    });
        } else if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                    .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testEventStoreExists(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testEventStoreExists ===");
        System.err.flush();
        logger.info("=== TEST: EVENT STORE EXISTS ===");

        // Use the setups details endpoint (not status) to get full setup information including event stores
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId)
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Status response code: {}", response.statusCode());
                    logger.info("Status response body: {}", response.bodyAsString());
                    
                    assertEquals(200, response.statusCode(), "Setup status should be available");
                    
                    // Parse JSON manually to handle potential errors
                    JsonObject responseBody;
                    try {
                        responseBody = response.bodyAsJsonObject();
                    } catch (Exception e) {
                        logger.error("Failed to parse JSON response: {}", e.getMessage());
                        throw new AssertionError("Response should be valid JSON: " + response.bodyAsString(), e);
                    }
                    
                    assertTrue(responseBody.containsKey("eventStores"), "Response should contain eventStores");
                    // eventStores is now an array of event store names, not a map
                    JsonArray eventStores = responseBody.getJsonArray("eventStores");
                    assertTrue(eventStores.contains("test_events"), 
                            "Event store 'test_events' should exist in the array");

                    logger.info("✅ Event store exists verification passed");
                    System.err.println("=== TEST METHOD COMPLETED: testEventStoreExists ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testStoreOneEvent(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testStoreOneEvent ===");
        System.err.flush();
        logger.info("=== TEST: STORE ONE EVENT ===");

        JsonObject eventRequest = new JsonObject()
                .put("eventType", "OrderCreated")
                .put("eventData", new JsonObject()
                        .put("orderId", "12345")
                        .put("amount", 99.99))
                .put("correlationId", "test-correlation-1")
                .put("validFrom", Instant.now().toString())
                .put("validTo", Instant.now().plusSeconds(86400).toString()); // +1 day

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Store event response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() != 200) {
                        logger.error("❌ Event storage failed: {} - {}", response.statusCode(), response.bodyAsString());
                    }

                    assertEquals(200, response.statusCode(), "Event should be stored successfully. Got error: " + response.bodyAsString());

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("eventId"), "Response should contain eventId");
                    
                    logger.info("✅ Event stored successfully with ID: {}", responseBody.getString("eventId"));
                    System.err.println("=== TEST METHOD COMPLETED: testStoreOneEvent ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetAllVersionsOfEvent(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetAllVersionsOfEvent ===");
        System.err.flush();
        logger.info("=== TEST: GET ALL VERSIONS OF EVENT ===");

        // First, store an initial event
        JsonObject eventRequest = new JsonObject()
                .put("eventType", "ProductPriceChanged")
                .put("eventData", new JsonObject()
                        .put("productId", "PROD-001")
                        .put("price", 100.0))
                .put("correlationId", "test-versions-1")
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(200, storeResponse.statusCode(), "Initial event should be stored");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    logger.info("Event stored with ID: {}", eventId);

                    // Store a correction (new version of the same event)
                    JsonObject correctionRequest = new JsonObject()
                            .put("eventType", "ProductPriceChanged")
                            .put("eventData", new JsonObject()
                                    .put("productId", "PROD-001")
                                    .put("price", 95.0)) // Corrected price
                            .put("correlationId", "test-versions-1")
                            .put("validFrom", Instant.now().toString());

                    return webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(correctionRequest)
                            .compose(correctionResponse -> {
                                testContext.verify(() -> {
                                    assertEquals(200, correctionResponse.statusCode(), "Correction should be stored");
                                });

                                // Now get all versions
                                return webClient.get(TEST_PORT, "localhost", 
                                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/versions")
                                        .timeout(10000)
                                        .send();
                            });
                })
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Get versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve event versions");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("versions"), "Response should contain versions");
                    
                    JsonArray versions = responseBody.getJsonArray("versions");
                    assertTrue(versions.size() >= 1, "Should have at least one version");

                    logger.info("✅ Retrieved {} version(s) of the event", versions.size());
                    System.err.println("=== TEST METHOD COMPLETED: testGetAllVersionsOfEvent ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetEventAsOfTransactionTime(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetEventAsOfTransactionTime ===");
        System.err.flush();
        logger.info("=== TEST: GET EVENT AS OF TRANSACTION TIME ===");

        // Store an event and capture its transaction time
        JsonObject eventRequest = new JsonObject()
                .put("eventType", "StockLevelChanged")
                .put("eventData", new JsonObject()
                        .put("productId", "PROD-002")
                        .put("quantity", 100))
                .put("correlationId", "test-temporal-1")
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(200, storeResponse.statusCode(), "Event should be stored");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    String transactionTime = storeResponse.bodyAsJsonObject().getString("transactionTime");
                    logger.info("Event stored with ID: {} at transaction time: {}", eventId, transactionTime);

                    // Store an update (creating a new version)
                    JsonObject updateRequest = new JsonObject()
                            .put("eventType", "StockLevelChanged")
                            .put("eventData", new JsonObject()
                                    .put("productId", "PROD-002")
                                    .put("quantity", 75)) // Updated quantity
                            .put("correlationId", "test-temporal-1")
                            .put("validFrom", Instant.now().toString());

                    return webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(updateRequest)
                            .compose(updateResponse -> {
                                testContext.verify(() -> {
                                    assertEquals(200, updateResponse.statusCode(), "Update should be stored");
                                });

                                // Now get the event as of the original transaction time
                                return webClient.get(TEST_PORT, "localhost",
                                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at?transactionTime=" + transactionTime)
                                        .timeout(10000)
                                        .send();
                            });
                })
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Get as-of-time response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve event as of transaction time");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("event"), "Response should contain event");
                    
                    JsonObject event = responseBody.getJsonObject("event");
                    assertNotNull(event, "Event should not be null");
                    
                    // The original event should have quantity 100
                    JsonObject eventData = event.getJsonObject("eventData");
                    assertNotNull(eventData, "Event data should not be null");

                    logger.info("✅ Retrieved event as of transaction time: {}", event.encodePrettily());
                    System.err.println("=== TEST METHOD COMPLETED: testGetEventAsOfTransactionTime ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetEventStoreStats(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetEventStoreStats ===");
        System.err.flush();
        logger.info("=== TEST: GET EVENT STORE STATISTICS ===");

        // First, store a few events to populate statistics
        JsonObject event1 = new JsonObject()
                .put("eventType", "OrderCreated")
                .put("eventData", new JsonObject().put("orderId", "ORDER-001"))
                .put("validFrom", Instant.now().toString());

        JsonObject event2 = new JsonObject()
                .put("eventType", "OrderShipped")
                .put("eventData", new JsonObject().put("orderId", "ORDER-001"))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event1)
                .compose(r1 -> webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(event2))
                .compose(r2 -> {
                    // Now get statistics
                    return webClient.get(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/stats")
                            .timeout(10000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve event store stats");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("stats"), "Response should contain stats");

                    JsonObject stats = responseBody.getJsonObject("stats");
                    assertNotNull(stats, "Stats should not be null");
                    
                    // Verify stats structure
                    assertTrue(stats.containsKey("totalEvents"), "Stats should contain totalEvents");
                    assertTrue(stats.containsKey("eventCountsByType"), "Stats should contain eventCountsByType");
                    
                    long totalEvents = stats.getLong("totalEvents");
                    assertTrue(totalEvents >= 2, "Should have at least 2 events (we just stored them). Found: " + totalEvents);

                    logger.info("✅ Event store statistics retrieved: {} total events", totalEvents);
                    System.err.println("=== TEST METHOD COMPLETED: testGetEventStoreStats ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ============================================================================
    // EDGE CASE TESTS - Critical Infrastructure Validation
    // ============================================================================

    @Test
    void testGetEventWithInvalidEventId(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetEventWithInvalidEventId ===");
        System.err.flush();
        logger.info("=== TEST: GET EVENT WITH INVALID EVENT ID ===");

        String invalidEventId = "non-existent-event-12345";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + invalidEventId)
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid event ID response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), 
                            "Should return 404 for non-existent event");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("✅ Invalid event ID properly handled with 404");
                    System.err.println("=== TEST METHOD COMPLETED: testGetEventWithInvalidEventId ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetAllVersionsWithInvalidEventId(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetAllVersionsWithInvalidEventId ===");
        System.err.flush();
        logger.info("=== TEST: GET ALL VERSIONS WITH INVALID EVENT ID ===");

        String invalidEventId = "invalid-event-99999";

        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + invalidEventId + "/versions")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), 
                            "Should return 200 with empty versions array");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("versions"), "Response should contain versions");

                    JsonArray versions = responseBody.getJsonArray("versions");
                    assertEquals(0, versions.size(), "Should have zero versions for non-existent event");

                    logger.info("✅ Invalid event ID in getAllVersions returns empty array");
                    System.err.println("=== TEST METHOD COMPLETED: testGetAllVersionsWithInvalidEventId ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetAsOfTransactionTimeWithInvalidFormat(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetAsOfTransactionTimeWithInvalidFormat ===");
        System.err.flush();
        logger.info("=== TEST: GET AS OF TRANSACTION TIME WITH INVALID FORMAT ===");

        // First store an event to get a valid event ID
        JsonObject eventRequest = new JsonObject()
                .put("eventType", "TestEvent")
                .put("eventData", new JsonObject().put("test", "data"))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(200, storeResponse.statusCode(), "Event should be stored");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    String invalidTimestamp = "not-a-valid-timestamp";

                    // Try to get event with invalid timestamp format
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at?transactionTime=" + invalidTimestamp)
                            .timeout(10000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid timestamp response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for invalid timestamp format");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Invalid transactionTime format"),
                            "Error message should indicate invalid format");

                    logger.info("✅ Invalid timestamp format properly rejected with 400");
                    System.err.println("=== TEST METHOD COMPLETED: testGetAsOfTransactionTimeWithInvalidFormat ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetAsOfTransactionTimeWithMissingParameter(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetAsOfTransactionTimeWithMissingParameter ===");
        System.err.flush();
        logger.info("=== TEST: GET AS OF TRANSACTION TIME WITH MISSING PARAMETER ===");

        // Store an event first
        JsonObject eventRequest = new JsonObject()
                .put("eventType", "TestEvent")
                .put("eventData", new JsonObject().put("test", "data"))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(200, storeResponse.statusCode(), "Event should be stored");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");

                    // Try to get event WITHOUT transactionTime parameter
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at")
                            .timeout(10000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Missing parameter response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for missing transactionTime parameter");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("transactionTime parameter is required"),
                            "Error message should indicate missing parameter");

                    logger.info("✅ Missing transactionTime parameter properly rejected with 400");
                    System.err.println("=== TEST METHOD COMPLETED: testGetAsOfTransactionTimeWithMissingParameter ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testQueryNonExistentEventStore(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testQueryNonExistentEventStore ===");
        System.err.flush();
        logger.info("=== TEST: QUERY NON-EXISTENT EVENT STORE ===");

        String nonExistentStore = "non_existent_store";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/" + nonExistentStore + "/events")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Non-existent store response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(500, response.statusCode(), 
                            "Should return 500 for non-existent event store");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Event store not found"),
                            "Error message should indicate store not found");

                    logger.info("✅ Non-existent event store properly rejected");
                    System.err.println("=== TEST METHOD COMPLETED: testQueryNonExistentEventStore ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testStoreEventWithInvalidJsonPayload(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testStoreEventWithInvalidJsonPayload ===");
        System.err.flush();
        logger.info("=== TEST: STORE EVENT WITH INVALID JSON PAYLOAD ===");

        String invalidJson = "{this is not valid json";

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(invalidJson))
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid JSON response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for invalid JSON");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("✅ Invalid JSON payload properly rejected with 400");
                    System.err.println("=== TEST METHOD COMPLETED: testStoreEventWithInvalidJsonPayload ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testStoreEventWithMissingRequiredFields(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testStoreEventWithMissingRequiredFields ===");
        System.err.flush();
        logger.info("=== TEST: STORE EVENT WITH MISSING REQUIRED FIELDS ===");

        // Missing eventType and eventData
        JsonObject incompleteEvent = new JsonObject()
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(incompleteEvent)
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Missing fields response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Should return error (400 or 500 depending on validation)
                    assertTrue(response.statusCode() >= 400, 
                            "Should return error status for missing required fields");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("✅ Missing required fields properly rejected");
                    System.err.println("=== TEST METHOD COMPLETED: testStoreEventWithMissingRequiredFields ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testGetStatsForNonExistentEventStore(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testGetStatsForNonExistentEventStore ===");
        System.err.flush();
        logger.info("=== TEST: GET STATS FOR NON-EXISTENT EVENT STORE ===");

        String nonExistentStore = "non_existent_stats_store";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/" + nonExistentStore + "/stats")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Non-existent store stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(500, response.statusCode(), 
                            "Should return 500 for non-existent event store");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Event store not found"),
                            "Error message should indicate store not found");

                    logger.info("✅ Stats request for non-existent store properly rejected");
                    System.err.println("=== TEST METHOD COMPLETED: testGetStatsForNonExistentEventStore ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testQueryWithInvalidSetupId(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testQueryWithInvalidSetupId ===");
        System.err.flush();
        logger.info("=== TEST: QUERY WITH INVALID SETUP ID ===");

        String invalidSetupId = "invalid_setup_12345";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + invalidSetupId + "/test_events/events")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid setup ID response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(500, response.statusCode(), 
                            "Should return 500 for invalid setup ID");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("✅ Invalid setup ID properly rejected");
                    System.err.println("=== TEST METHOD COMPLETED: testQueryWithInvalidSetupId ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testConcurrentEventStores(VertxTestContext testContext) {
        System.err.println("=== TEST METHOD STARTED: testConcurrentEventStores ===");
        System.err.flush();
        logger.info("=== TEST: CONCURRENT EVENT STORES ===");

        // Store events concurrently to test thread safety
        JsonObject event1 = new JsonObject()
                .put("eventType", "ConcurrentTest1")
                .put("eventData", new JsonObject().put("value", 1))
                .put("validFrom", Instant.now().toString());

        JsonObject event2 = new JsonObject()
                .put("eventType", "ConcurrentTest2")
                .put("eventData", new JsonObject().put("value", 2))
                .put("validFrom", Instant.now().toString());

        JsonObject event3 = new JsonObject()
                .put("eventType", "ConcurrentTest3")
                .put("eventData", new JsonObject().put("value", 3))
                .put("validFrom", Instant.now().toString());

        // Fire all three requests concurrently
        io.vertx.core.Future<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future1 = 
            webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event1);

        io.vertx.core.Future<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future2 = 
            webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event2);

        io.vertx.core.Future<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future3 = 
            webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event3);

        io.vertx.core.Future.all(future1, future2, future3)
                .onSuccess(composite -> testContext.verify(() -> {
                    logger.info("Concurrent events response status codes: {}, {}, {}", 
                            future1.result().statusCode(), 
                            future2.result().statusCode(),
                            future3.result().statusCode());

                    assertEquals(200, future1.result().statusCode(), "Event 1 should be stored");
                    assertEquals(200, future2.result().statusCode(), "Event 2 should be stored");
                    assertEquals(200, future3.result().statusCode(), "Event 3 should be stored");

                    logger.info("✅ Concurrent event storage successful - thread safety validated");
                    System.err.println("=== TEST METHOD COMPLETED: testConcurrentEventStores ===");
                    System.err.flush();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
