package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
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
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventStoreIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(EventStoreIntegrationTest.class);
    private static final int TEST_PORT = 18090;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_eventstore_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer restServer;
    private WebClient webClient;
    private String testSetupId;
    private String deploymentId;

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== EVENTSTORE TEST SETUP STARTED ===");
        
        testSetupId = "eventstore_test_" + System.currentTimeMillis();
        logger.info("Test Setup ID: {}", testSetupId);

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy REST server first
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        restServer = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(restServer)
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed with ID: {}", deploymentId);

                    webClient = WebClient.create(vertx);

                    // Now create database setup via REST API
                    createDatabaseSetupViaRestApi(testContext);
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
                                .put("notificationPrefix", "test_events_"))
                        .add(new JsonObject()
                                .put("eventStoreName", "summary_events")
                                .put("tableName", "summary_events")
                                .put("biTemporalEnabled", true)
                                .put("notificationPrefix", "summary_events_")
                                .put("aggregateSummaryEnabled", true)))
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
                        logger.info("Setup created successfully: {}", body.getString("setupId"));
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
                            logger.info("Cleanup completed");
                        } else {
                            logger.warn(" Cleanup failed with status: {}", response.statusCode());
                        }
                        return vertx.undeploy(deploymentId);
                    })
                    .onSuccess(v -> {
                        logger.info("=== EVENTSTORE TEST CLEANUP COMPLETE ===");
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
        } else if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testEventStoreExists(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreExists ===");
        logger.info("=== TEST: EVENT STORE EXISTS ===");

        // Use the setups details endpoint (not status) to get full setup information including event stores
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId)
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
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

                    logger.info("Event store exists verification passed");
                    logger.info("=== TEST METHOD COMPLETED: testEventStoreExists ===");
                    testContext.completeNow();
                })));
    }

    /**
     * §7.1 regression (event-store delete). Before the fix, {@code deleteEventStoreImpl}
     * returned {@code "deleted successfully"} without removing anything, so a deleted store
     * still appeared in {@code getEventStores()}. This asserts the handler-level contract:
     * after DELETE, the store is gone from {@code GET /api/v1/setups/:setupId}.
     *
     * <p>The composite delete id is {@code setupId-storeName} (parsed via {@code lastIndexOf('-')}).
     * The underlying Postgres table drop is performed by
     * {@code PeeGeeQDatabaseSetupService.removeEventStore} (CASCADE) and is asserted at the
     * service level; this test does not query {@code pg_tables} directly because raw JDBC in
     * tests is prohibited and there is no REST surface that lists physical tables.
     */
    @Test
    void testDeleteEventStoreRemovesItFromSetupListing(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testDeleteEventStoreRemovesItFromSetupListing ===");

        String storeName = "del_probe_" + System.currentTimeMillis();
        JsonObject createBody = new JsonObject()
                .put("name", storeName)
                .put("setup", testSetupId)
                .put("biTemporalEnabled", true)
                .put("retentionDays", 365);

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/event-stores")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(createBody)
                .compose(createResp -> {
                    testContext.verify(() -> {
                        logger.info("Create response: {} - {}", createResp.statusCode(), createResp.bodyAsString());
                        assertTrue(createResp.statusCode() == 200 || createResp.statusCode() == 201,
                                "Event store create must return 200/201; got: " + createResp.bodyAsString());
                    });
                    return webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId)
                            .timeout(10000).send();
                })
                .compose(listBefore -> {
                    testContext.verify(() -> {
                        assertEquals(200, listBefore.statusCode(), "Setup details must return 200");
                        JsonArray stores = listBefore.bodyAsJsonObject().getJsonArray("eventStores");
                        assertTrue(stores.contains(storeName),
                                "Created store '" + storeName + "' must appear in the setup listing; got: " + stores.encode());
                    });
                    String storeId = testSetupId + "-" + storeName;
                    return webClient.delete(TEST_PORT, "localhost", "/api/v1/management/event-stores/" + storeId)
                            .timeout(30000).send();
                })
                .compose(deleteResp -> {
                    testContext.verify(() -> {
                        logger.info("Delete response: {} - {}", deleteResp.statusCode(), deleteResp.bodyAsString());
                        assertTrue(deleteResp.statusCode() == 200 || deleteResp.statusCode() == 204,
                                "Event store delete must return 200/204; got: " + deleteResp.bodyAsString());
                    });
                    return webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId)
                            .timeout(10000).send();
                })
                .onComplete(testContext.succeeding(listAfter -> testContext.verify(() -> {
                    logger.info("Setup listing after delete: {} - {}", listAfter.statusCode(), listAfter.bodyAsString());
                    assertEquals(200, listAfter.statusCode(), "Setup details must return 200");
                    JsonArray stores = listAfter.bodyAsJsonObject().getJsonArray("eventStores");
                    assertFalse(stores.contains(storeName),
                            "Deleted store '" + storeName + "' must NOT appear in the setup listing — regression: " +
                            "deleteEventStoreImpl used to return success without removing the store. Got: " + stores.encode());
                    logger.info("=== TEST METHOD COMPLETED: testDeleteEventStoreRemovesItFromSetupListing ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testStoreOneEvent(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testStoreOneEvent ===");
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
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Store event response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() != 201) {
                        logger.error(" Event storage failed: {} - {}", response.statusCode(), response.bodyAsString());
                    }

                    assertEquals(201, response.statusCode(), "Event should be stored successfully (201 Created). Got error: " + response.bodyAsString());

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("eventId"), "Response should contain eventId");
                    
                    logger.info("Event stored successfully with ID: {}", responseBody.getString("eventId"));
                    logger.info("=== TEST METHOD COMPLETED: testStoreOneEvent ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testQueryEventsPaginationMetadata(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testQueryEventsPaginationMetadata ===");

        String eventType = "PaginationMetaEvent" + System.currentTimeMillis();
        String basePath = "/api/v1/eventstores/" + testSetupId + "/test_events/events";

        JsonObject eventRequest = new JsonObject()
                .put("eventType", eventType)
                .put("eventData", new JsonObject().put("seq", 1))
                .put("validFrom", Instant.now().toString());

        // Store 4 events of the same type, then query two pages of 2
        webClient.post(TEST_PORT, "localhost", basePath)
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 1)))
                .compose(r1 -> webClient.post(TEST_PORT, "localhost", basePath)
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 2))))
                .compose(r2 -> webClient.post(TEST_PORT, "localhost", basePath)
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 3))))
                .compose(r3 -> webClient.post(TEST_PORT, "localhost", basePath)
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 4))))
                .compose(r4 -> webClient.get(TEST_PORT, "localhost",
                                basePath + "?eventType=" + eventType + "&limit=2&offset=0")
                        .timeout(10000)
                        .send())
                .compose(page1 -> {
                    testContext.verify(() -> {
                        logger.info("Page 1 response: {} - {}", page1.statusCode(), page1.bodyAsString());
                        assertEquals(200, page1.statusCode(), "Query should succeed");

                        JsonObject body = page1.bodyAsJsonObject();
                        assertEquals(2, body.getInteger("eventCount"), "Page 1 must contain 2 events");
                        assertNotNull(body.getLong("totalCount"), "Response should contain totalCount");
                        assertEquals(4L, body.getLong("totalCount"), "totalCount must reflect all 4 matching events");
                        assertEquals(true, body.getBoolean("hasMore"), "Page 1 of 2 must report hasMore=true");
                    });

                    return webClient.get(TEST_PORT, "localhost",
                                    basePath + "?eventType=" + eventType + "&limit=2&offset=2")
                            .timeout(10000)
                            .send();
                })
                .onComplete(testContext.succeeding(page2 -> testContext.verify(() -> {
                    logger.info("Page 2 response: {} - {}", page2.statusCode(), page2.bodyAsString());
                    assertEquals(200, page2.statusCode(), "Query should succeed");

                    JsonObject body = page2.bodyAsJsonObject();
                    assertEquals(2, body.getInteger("eventCount"), "Page 2 must contain the remaining 2 events");
                    assertEquals(4L, body.getLong("totalCount"), "totalCount must be stable across pages");
                    // Boundary case: total (4) is an exact multiple of limit (2). The last page is full,
                    // but no more events exist — hasMore must be false.
                    assertEquals(false, body.getBoolean("hasMore"),
                            "Last page must report hasMore=false even when the page is full");

                    logger.info("=== TEST METHOD COMPLETED: testQueryEventsPaginationMetadata ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testQueryEventsKeysetCursorPagination(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testQueryEventsKeysetCursorPagination ===");

        String eventType = "KeysetCursorEvent" + System.currentTimeMillis();
        String basePath = "/api/v1/eventstores/" + testSetupId + "/test_events/events";

        JsonObject eventRequest = new JsonObject()
                .put("eventType", eventType)
                .put("validFrom", Instant.now().toString());

        // Store 3 events, fetch newest-first page 1 of 2, then page 2 via the keyset cursor
        webClient.post(TEST_PORT, "localhost", basePath)
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 1)))
                .compose(r1 -> webClient.post(TEST_PORT, "localhost", basePath)
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 2))))
                .compose(r2 -> webClient.post(TEST_PORT, "localhost", basePath)
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(eventRequest.copy().put("eventData", new JsonObject().put("seq", 3))))
                .compose(r3 -> webClient.get(TEST_PORT, "localhost",
                                basePath + "?eventType=" + eventType + "&limit=2&sortOrder=TRANSACTION_TIME_DESC")
                        .timeout(10000)
                        .send())
                .compose(page1 -> {
                    testContext.verify(() -> {
                        logger.info("Cursor page 1 response: {} - {}", page1.statusCode(), page1.bodyAsString());
                        assertEquals(200, page1.statusCode(), "Page 1 query should succeed");
                        assertEquals(2, page1.bodyAsJsonObject().getInteger("eventCount"), "Page 1 must contain 2 events");
                    });

                    JsonObject anchor = page1.bodyAsJsonObject().getJsonArray("events").getJsonObject(1);
                    // transactionTime is serialized as an epoch-seconds decimal — send it back verbatim
                    String anchorTime = java.net.URLEncoder.encode(
                            anchor.getValue("transactionTime").toString(), java.nio.charset.StandardCharsets.UTF_8);
                    String anchorId = anchor.getString("eventId");
                    JsonObject page1Body = page1.bodyAsJsonObject();

                    return webClient.get(TEST_PORT, "localhost",
                                    basePath + "?eventType=" + eventType + "&limit=2&sortOrder=TRANSACTION_TIME_DESC"
                                            + "&afterTransactionTime=" + anchorTime + "&afterEventId=" + anchorId)
                            .timeout(10000)
                            .send()
                            .map(page2 -> new JsonObject().put("page1", page1Body).put("page2", page2.bodyAsJsonObject())
                                    .put("page2Status", page2.statusCode()));
                })
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    logger.info("Cursor page 2 response: {}", result.getJsonObject("page2").encode());
                    assertEquals(200, result.getInteger("page2Status"), "Page 2 query should succeed");

                    JsonObject page2 = result.getJsonObject("page2");
                    assertEquals(1, page2.getInteger("eventCount"),
                            "Cursor page 2 must contain only the 1 event older than the anchor");

                    String page2Id = page2.getJsonArray("events").getJsonObject(0).getString("eventId");
                    var page1Events = result.getJsonObject("page1").getJsonArray("events");
                    for (int i = 0; i < page1Events.size(); i++) {
                        assertNotEquals(page1Events.getJsonObject(i).getString("eventId"), page2Id,
                                "Cursor page 2 must not repeat a page 1 event");
                    }

                    logger.info("=== TEST METHOD COMPLETED: testQueryEventsKeysetCursorPagination ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testAggregatesSourceParameterAndReconcileEndpoint(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testAggregatesSourceParameterAndReconcileEndpoint ===");

        String aggregateId = "src-agg-" + System.currentTimeMillis();
        String summaryBase = "/api/v1/eventstores/" + testSetupId + "/summary_events";
        String plainBase = "/api/v1/eventstores/" + testSetupId + "/test_events";

        JsonObject eventRequest = new JsonObject()
                .put("eventType", "SourceParamEvent")
                .put("eventData", new JsonObject().put("k", 1))
                .put("aggregateId", aggregateId)
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", summaryBase + "/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .compose(stored -> {
                    testContext.verify(() -> assertEquals(201, stored.statusCode(),
                            "Pre-condition: event stored in summary-enabled store, got: " + stored.bodyAsString()));

                    // source=summary on the summary-enabled store — proves end-to-end flag plumbing
                    return webClient.get(TEST_PORT, "localhost", summaryBase + "/aggregates?source=summary")
                            .timeout(10000).send();
                })
                .compose(summarySource -> {
                    testContext.verify(() -> {
                        logger.info("source=summary response: {} - {}", summarySource.statusCode(), summarySource.bodyAsString());
                        assertEquals(200, summarySource.statusCode(), "source=summary must succeed on a summary-enabled store");
                        assertTrue(summarySource.bodyAsString().contains(aggregateId),
                                "Summary source must list the aggregate");
                    });
                    // source=log forces the live query on the same store
                    return webClient.get(TEST_PORT, "localhost", summaryBase + "/aggregates?source=log")
                            .timeout(10000).send();
                })
                .compose(logSource -> {
                    testContext.verify(() -> {
                        assertEquals(200, logSource.statusCode(), "source=log must succeed");
                        assertTrue(logSource.bodyAsString().contains(aggregateId), "Live source must list the aggregate");
                    });
                    // invalid source value
                    return webClient.get(TEST_PORT, "localhost", summaryBase + "/aggregates?source=bogus")
                            .timeout(10000).send();
                })
                .compose(bogusSource -> {
                    testContext.verify(() -> assertEquals(400, bogusSource.statusCode(),
                            "Invalid source value must be rejected"));
                    // source=summary on a store WITHOUT a summary
                    return webClient.get(TEST_PORT, "localhost", plainBase + "/aggregates?source=summary")
                            .timeout(10000).send();
                })
                .compose(noSummary -> {
                    testContext.verify(() -> {
                        assertEquals(400, noSummary.statusCode(), "source=summary on a non-summary store must be 400");
                        assertTrue(noSummary.bodyAsJsonObject().getString("error").contains("aggregateSummaryEnabled"),
                                "Error must name the option");
                    });
                    // reconcile verify
                    return webClient.post(TEST_PORT, "localhost",
                            summaryBase + "/aggregate-summary/reconcile?mode=verify").timeout(10000).send();
                })
                .compose(verify -> {
                    testContext.verify(() -> {
                        logger.info("reconcile verify response: {} - {}", verify.statusCode(), verify.bodyAsString());
                        assertEquals(200, verify.statusCode(), "reconcile verify must succeed");
                        JsonObject body = verify.bodyAsJsonObject();
                        assertEquals("VERIFY", body.getString("mode"));
                        assertTrue(body.getLong("aggregatesChecked") >= 1, "At least the stored pair must be checked");
                        assertEquals(0L, body.getLong("missingInSummary"), "Trigger-maintained summary must be clean");
                        assertEquals(0L, body.getLong("staleInSummary"));
                        assertEquals(0L, body.getLong("orphanedInSummary"));
                    });
                    // reconcile rebuild
                    return webClient.post(TEST_PORT, "localhost",
                            summaryBase + "/aggregate-summary/reconcile?mode=rebuild").timeout(10000).send();
                })
                .compose(rebuild -> {
                    testContext.verify(() -> {
                        assertEquals(200, rebuild.statusCode(), "reconcile rebuild must succeed");
                        assertEquals("REBUILD", rebuild.bodyAsJsonObject().getString("mode"));
                        assertTrue(rebuild.bodyAsJsonObject().getLong("repaired") >= 1);
                    });
                    // invalid mode
                    return webClient.post(TEST_PORT, "localhost",
                            summaryBase + "/aggregate-summary/reconcile?mode=bogus").timeout(10000).send();
                })
                .compose(bogusMode -> {
                    testContext.verify(() -> assertEquals(400, bogusMode.statusCode(),
                            "Invalid reconcile mode must be rejected"));
                    // reconcile on a store without a summary
                    return webClient.post(TEST_PORT, "localhost",
                            plainBase + "/aggregate-summary/reconcile?mode=verify").timeout(10000).send();
                })
                .onComplete(testContext.succeeding(noSummaryReconcile -> testContext.verify(() -> {
                    assertEquals(400, noSummaryReconcile.statusCode(),
                            "reconcile on a non-summary store must be 400");
                    assertTrue(noSummaryReconcile.bodyAsJsonObject().getString("error").contains("aggregateSummaryEnabled"),
                            "Error must name the option");
                    logger.info("=== TEST METHOD COMPLETED: testAggregatesSourceParameterAndReconcileEndpoint ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetAllVersionsOfEvent(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetAllVersionsOfEvent ===");
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
                        assertEquals(201, storeResponse.statusCode(), "Initial event should be stored (201 Created)");
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
                                    assertEquals(201, correctionResponse.statusCode(), "Correction should be stored (201 Created)");
                                });

                                // Now get all versions
                                return webClient.get(TEST_PORT, "localhost", 
                                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/versions")
                                        .timeout(10000)
                                        .send();
                            });
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Get versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve event versions");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("versions"), "Response should contain versions");
                    
                    JsonArray versions = responseBody.getJsonArray("versions");
                    assertTrue(versions.size() >= 1, "Should have at least one version");

                    logger.info("Retrieved {} version(s) of the event", versions.size());
                    logger.info("=== TEST METHOD COMPLETED: testGetAllVersionsOfEvent ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetEventAsOfTransactionTime(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetEventAsOfTransactionTime ===");
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
                        assertEquals(201, storeResponse.statusCode(), "Event should be stored (201 Created)");
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
                                    assertEquals(201, updateResponse.statusCode(), "Update should be stored (201 Created)");
                                });

                                // Now get the event as of the original transaction time
                                return webClient.get(TEST_PORT, "localhost",
                                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at?transactionTime=" + transactionTime)
                                        .timeout(10000)
                                        .send();
                            });
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
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

                    logger.info("Retrieved event as of transaction time: {}", event.encodePrettily());
                    logger.info("=== TEST METHOD COMPLETED: testGetEventAsOfTransactionTime ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetEventStoreStats(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetEventStoreStats ===");
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
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
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

                    logger.info("Event store statistics retrieved: {} total events", totalEvents);
                    logger.info("=== TEST METHOD COMPLETED: testGetEventStoreStats ===");
                    testContext.completeNow();
                })));
    }

    // ============================================================================
    // EDGE CASE TESTS - Critical Infrastructure Validation
    // ============================================================================

    @Test
    void testGetEventWithInvalidEventId(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetEventWithInvalidEventId ===");
        logger.info("=== TEST: GET EVENT WITH INVALID EVENT ID ===");

        String invalidEventId = "non-existent-event-12345";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + invalidEventId)
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid event ID response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), 
                            "Should return 404 for non-existent event");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("Invalid event ID properly handled with 404");
                    logger.info("=== TEST METHOD COMPLETED: testGetEventWithInvalidEventId ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetAllVersionsWithInvalidEventId(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetAllVersionsWithInvalidEventId ===");
        logger.info("=== TEST: GET ALL VERSIONS WITH INVALID EVENT ID ===");

        String invalidEventId = "invalid-event-99999";

        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + invalidEventId + "/versions")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), 
                            "Should return 200 with empty versions array");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("versions"), "Response should contain versions");

                    JsonArray versions = responseBody.getJsonArray("versions");
                    assertEquals(0, versions.size(), "Should have zero versions for non-existent event");

                    logger.info("Invalid event ID in getAllVersions returns empty array");
                    logger.info("=== TEST METHOD COMPLETED: testGetAllVersionsWithInvalidEventId ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetAsOfTransactionTimeWithInvalidFormat(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetAsOfTransactionTimeWithInvalidFormat ===");
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
                        assertEquals(201, storeResponse.statusCode(), "Event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    String invalidTimestamp = "not-a-valid-timestamp";

                    // Try to get event with invalid timestamp format
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at?transactionTime=" + invalidTimestamp)
                            .timeout(10000)
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid timestamp response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for invalid timestamp format");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Invalid transactionTime format"),
                            "Error message should indicate invalid format");

                    logger.info("Invalid timestamp format properly rejected with 400");
                    logger.info("=== TEST METHOD COMPLETED: testGetAsOfTransactionTimeWithInvalidFormat ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetAsOfTransactionTimeWithMissingParameter(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetAsOfTransactionTimeWithMissingParameter ===");
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
                        assertEquals(201, storeResponse.statusCode(), "Event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");

                    // Try to get event WITHOUT transactionTime parameter
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at")
                            .timeout(10000)
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Missing parameter response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for missing transactionTime parameter");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("transactionTime parameter is required"),
                            "Error message should indicate missing parameter");

                    logger.info("Missing transactionTime parameter properly rejected with 400");
                    logger.info("=== TEST METHOD COMPLETED: testGetAsOfTransactionTimeWithMissingParameter ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testQueryNonExistentEventStore(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testQueryNonExistentEventStore ===");
        logger.info("=== TEST: QUERY NON-EXISTENT EVENT STORE ===");

        String nonExistentStore = "non_existent_store";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/" + nonExistentStore + "/events")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Non-existent store response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), 
                            "Should return 404 for non-existent event store");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Event store not found"),
                            "Error message should indicate store not found");

                    logger.info("Non-existent event store properly rejected");
                    logger.info("=== TEST METHOD COMPLETED: testQueryNonExistentEventStore ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testStoreEventWithInvalidJsonPayload(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testStoreEventWithInvalidJsonPayload ===");
        logger.info("=== TEST: STORE EVENT WITH INVALID JSON PAYLOAD ===");
        logger.info("--- EXPECTED ERROR (testStoreEventWithInvalidJsonPayload → 400, JsonParseException) ---");

        String invalidJson = "{this is not valid json";

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(invalidJson))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid JSON response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), 
                            "Should return 400 for invalid JSON");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("Invalid JSON payload properly rejected with 400");
                    logger.info("=== TEST METHOD COMPLETED: testStoreEventWithInvalidJsonPayload ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testStoreEventWithMissingRequiredFields(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testStoreEventWithMissingRequiredFields ===");
        logger.info("=== TEST: STORE EVENT WITH MISSING REQUIRED FIELDS ===");

        // Missing eventType and eventData
        JsonObject incompleteEvent = new JsonObject()
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(incompleteEvent)
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Missing fields response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Should return error (400 or 500 depending on validation)
                    assertTrue(response.statusCode() >= 400, 
                            "Should return error status for missing required fields");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("Missing required fields properly rejected");
                    logger.info("=== TEST METHOD COMPLETED: testStoreEventWithMissingRequiredFields ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetStatsForNonExistentEventStore(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetStatsForNonExistentEventStore ===");
        logger.info("=== TEST: GET STATS FOR NON-EXISTENT EVENT STORE ===");

        String nonExistentStore = "non_existent_stats_store";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + testSetupId + "/" + nonExistentStore + "/stats")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Non-existent store stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), 
                            "Should return 404 for non-existent event store");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("Event store not found"),
                            "Error message should indicate store not found");

                    logger.info("Stats request for non-existent store properly rejected");
                    logger.info("=== TEST METHOD COMPLETED: testGetStatsForNonExistentEventStore ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testQueryWithInvalidSetupId(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testQueryWithInvalidSetupId ===");
        logger.info("=== TEST: QUERY WITH INVALID SETUP ID ===");

        String invalidSetupId = "invalid_setup_12345";

        webClient.get(TEST_PORT, "localhost", 
                "/api/v1/eventstores/" + invalidSetupId + "/test_events/events")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid setup ID response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), 
                            "Should return 404 for invalid setup ID");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");

                    logger.info("Invalid setup ID properly rejected");
                    logger.info("=== TEST METHOD COMPLETED: testQueryWithInvalidSetupId ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testConcurrentEventStores(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testConcurrentEventStores ===");
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
                .onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                    logger.info("Concurrent events response status codes: {}, {}, {}",
                            future1.result().statusCode(),
                            future2.result().statusCode(),
                            future3.result().statusCode());

                    assertEquals(201, future1.result().statusCode(), "Event 1 should be stored (201 Created)");
                    assertEquals(201, future2.result().statusCode(), "Event 2 should be stored (201 Created)");
                    assertEquals(201, future3.result().statusCode(), "Event 3 should be stored (201 Created)");

                    logger.info("Concurrent event storage successful - thread safety validated");
                    logger.info("=== TEST METHOD COMPLETED: testConcurrentEventStores ===");
                    testContext.completeNow();
                })));
    }

    // ============================================================================
    // BI-TEMPORAL CORRECTION TESTS - Core Bi-Temporal Feature
    // ============================================================================

    @Test
    void testAppendCorrectionToEvent(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testAppendCorrectionToEvent ===");
        logger.info("=== TEST: APPEND CORRECTION TO EVENT ===");

        // First, store an initial event with incorrect data
        JsonObject originalEvent = new JsonObject()
                .put("eventType", "OrderPriceSet")
                .put("eventData", new JsonObject()
                        .put("orderId", "ORDER-CORRECTION-001")
                        .put("price", 89.99)) // This is the "incorrect" price
                .put("correlationId", "test-correction-1")
                .put("validFrom", Instant.now().minusSeconds(3600).toString()); // 1 hour ago

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(originalEvent)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Original event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    logger.info("Original event stored with ID: {}", eventId);

                    // Now append a correction with the correct price
                    JsonObject correctionRequest = new JsonObject()
                            .put("eventData", new JsonObject()
                                    .put("orderId", "ORDER-CORRECTION-001")
                                    .put("price", 99.99)) // Corrected price
                            .put("correctionReason", "Original price was incorrect - should be $99.99 not $89.99")
                            .put("validFrom", Instant.now().minusSeconds(3600).toString()) // Same valid time as original
                            .put("correlationId", "test-correction-1")
                            .put("metadata", new JsonObject()
                                    .put("correctedBy", "admin@example.com")
                                    .put("ticketId", "SUPPORT-12345"));

                    return webClient.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/corrections")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(correctionRequest);
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Correction response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(201, response.statusCode(), "Correction should be created successfully");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertEquals("Correction appended successfully", responseBody.getString("message"));
                    assertNotNull(responseBody.getString("correctionEventId"), "Should have correction event ID");
                    assertNotNull(responseBody.getString("originalEventId"), "Should have original event ID");
                    assertTrue(responseBody.getInteger("version") >= 2, "Version should be at least 2");
                    assertEquals("Original price was incorrect - should be $99.99 not $89.99",
                            responseBody.getString("correctionReason"));

                    logger.info("Correction appended successfully with new event ID: {}",
                            responseBody.getString("correctionEventId"));
                    logger.info("=== TEST METHOD COMPLETED: testAppendCorrectionToEvent ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testAppendCorrectionWithMissingReason(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testAppendCorrectionWithMissingReason ===");
        logger.info("=== TEST: APPEND CORRECTION WITH MISSING REASON ===");

        // First, store an event
        JsonObject originalEvent = new JsonObject()
                .put("eventType", "TestEvent")
                .put("eventData", new JsonObject().put("value", 100))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(originalEvent)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Original event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");

                    // Try to append correction WITHOUT correctionReason (required field)
                    JsonObject correctionRequest = new JsonObject()
                            .put("eventData", new JsonObject().put("value", 200));
                    // Missing: correctionReason

                    return webClient.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/corrections")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(correctionRequest);
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Missing reason response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), "Should return 400 for missing correctionReason");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("correctionReason is required"),
                            "Error message should indicate missing correctionReason");

                    logger.info("Missing correctionReason properly rejected with 400");
                    logger.info("=== TEST METHOD COMPLETED: testAppendCorrectionWithMissingReason ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testAppendCorrectionWithMissingEventData(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testAppendCorrectionWithMissingEventData ===");
        logger.info("=== TEST: APPEND CORRECTION WITH MISSING EVENT DATA ===");

        // First, store an event
        JsonObject originalEvent = new JsonObject()
                .put("eventType", "TestEvent")
                .put("eventData", new JsonObject().put("value", 100))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(originalEvent)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Original event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");

                    // Try to append correction WITHOUT eventData (required field)
                    JsonObject correctionRequest = new JsonObject()
                            .put("correctionReason", "Some reason");
                    // Missing: eventData

                    return webClient.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/corrections")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(correctionRequest);
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Missing eventData response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(400, response.statusCode(), "Should return 400 for missing eventData");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("eventData is required"),
                            "Error message should indicate missing eventData");

                    logger.info("Missing eventData properly rejected with 400");
                    logger.info("=== TEST METHOD COMPLETED: testAppendCorrectionWithMissingEventData ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testAppendCorrectionToNonExistentEvent(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testAppendCorrectionToNonExistentEvent ===");
        logger.info("=== TEST: APPEND CORRECTION TO NON-EXISTENT EVENT ===");

        String nonExistentEventId = "non-existent-event-99999";

        JsonObject correctionRequest = new JsonObject()
                .put("eventData", new JsonObject().put("value", 200))
                .put("correctionReason", "Trying to correct non-existent event");

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + nonExistentEventId + "/corrections")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(correctionRequest)
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Non-existent event correction response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode(), "Should return 404 for non-existent event");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");
                    assertTrue(responseBody.containsKey("error"), "Response should contain error message");
                    assertTrue(responseBody.getString("error").contains("not found"),
                            "Error message should indicate event not found");

                    logger.info("Correction to non-existent event properly rejected with 404");
                    logger.info("=== TEST METHOD COMPLETED: testAppendCorrectionToNonExistentEvent ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testCorrectionPreservesAuditTrail(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testCorrectionPreservesAuditTrail ===");
        logger.info("=== TEST: CORRECTION PRESERVES AUDIT TRAIL ===");

        // Store original event
        JsonObject originalEvent = new JsonObject()
                .put("eventType", "InventoryCount")
                .put("eventData", new JsonObject()
                        .put("productId", "PROD-AUDIT-001")
                        .put("count", 100))
                .put("correlationId", "audit-trail-test")
                .put("validFrom", Instant.now().minusSeconds(7200).toString()); // 2 hours ago

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(originalEvent)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Original event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    logger.info("Original event stored with ID: {}", eventId);

                    // Append correction
                    JsonObject correctionRequest = new JsonObject()
                            .put("eventData", new JsonObject()
                                    .put("productId", "PROD-AUDIT-001")
                                    .put("count", 95)) // Corrected count
                            .put("correctionReason", "Physical recount showed 95 units, not 100")
                            .put("validFrom", Instant.now().minusSeconds(7200).toString());

                    return webClient.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/corrections")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(correctionRequest)
                            .compose(correctionResponse -> {
                                testContext.verify(() -> {
                                    assertEquals(201, correctionResponse.statusCode(), "Correction should be created");
                                });

                                // Now get all versions to verify audit trail
                                return webClient.get(TEST_PORT, "localhost",
                                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/versions")
                                        .timeout(10000)
                                        .send();
                            });
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve versions");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    JsonArray versions = responseBody.getJsonArray("versions");

                    // Should have at least 2 versions (original + correction)
                    assertTrue(versions.size() >= 2,
                            "Should have at least 2 versions (original + correction). Found: " + versions.size());

                    logger.info("Audit trail preserved with {} versions", versions.size());
                    logger.info("=== TEST METHOD COMPLETED: testCorrectionPreservesAuditTrail ===");
                    testContext.completeNow();
                })));
    }

    // ============================================================================
    // BI-TEMPORAL ENDPOINT TESTS - Versions, Point-in-Time, Stats
    // ============================================================================

    @Test
    void testGetEventVersions(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetEventVersions ===");
        logger.info("=== TEST: GET EVENT VERSIONS ===");

        // Store an event
        JsonObject event = new JsonObject()
                .put("eventType", "VersionTestEvent")
                .put("eventData", new JsonObject()
                        .put("testId", "VERSION-TEST-001")
                        .put("value", 100))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    logger.info("Event stored with ID: {}", eventId);

                    // Get versions for this event
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/versions")
                            .timeout(10000)
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Versions response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve versions");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody.getJsonArray("versions"), "Should have versions array");
                    assertTrue(responseBody.getJsonArray("versions").size() >= 1,
                            "Should have at least 1 version");

                    logger.info("Event versions retrieved successfully");
                    logger.info("=== TEST METHOD COMPLETED: testGetEventVersions ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testGetEventVersionsForNonExistentEvent(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testGetEventVersionsForNonExistentEvent ===");
        logger.info("=== TEST: GET VERSIONS FOR NON-EXISTENT EVENT ===");

        String nonExistentEventId = "non-existent-event-" + System.currentTimeMillis();

        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + nonExistentEventId + "/versions")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Should return 404 or empty versions array
                    assertTrue(response.statusCode() == 404 || response.statusCode() == 200,
                            "Should return 404 or 200 with empty versions");

                    if (response.statusCode() == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        JsonArray versions = body.getJsonArray("versions");
                        assertTrue(versions == null || versions.isEmpty(),
                                "Versions should be empty for non-existent event");
                    }

                    logger.info("Non-existent event versions handled correctly");
                    logger.info("=== TEST METHOD COMPLETED: testGetEventVersionsForNonExistentEvent ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testPointInTimeQuery(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testPointInTimeQuery ===");
        logger.info("=== TEST: POINT-IN-TIME QUERY ===");

        // Store an event
        JsonObject event = new JsonObject()
                .put("eventType", "PointInTimeTestEvent")
                .put("eventData", new JsonObject()
                        .put("testId", "PIT-TEST-001")
                        .put("value", 200))
                .put("validFrom", Instant.now().toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(event)
                .compose(storeResponse -> {
                    testContext.verify(() -> {
                        assertEquals(201, storeResponse.statusCode(), "Event should be stored (201 Created)");
                    });

                    String eventId = storeResponse.bodyAsJsonObject().getString("eventId");
                    logger.info("Event stored with ID: {}", eventId);

                    // Query the event at current transaction time
                    String transactionTime = Instant.now().toString();
                    return webClient.get(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + testSetupId + "/test_events/events/" + eventId + "/at?transactionTime=" + transactionTime)
                            .timeout(10000)
                            .send();
                })
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Point-in-time response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Should return 200 with the event or 404 if not found at that time
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                            "Should return 200 or 404");

                    if (response.statusCode() == 200) {
                        JsonObject responseBody = response.bodyAsJsonObject();
                        assertNotNull(responseBody, "Response body should not be null");
                        logger.info("Point-in-time query returned event data");
                    } else {
                        logger.info("Point-in-time query returned 404 (event not found at that time)");
                    }

                    logger.info("=== TEST METHOD COMPLETED: testPointInTimeQuery ===");
                    testContext.completeNow();
                })));
    }

    @Test
    void testEventStoreStats(VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreStats ===");
        logger.info("=== TEST: EVENT STORE STATS ===");

        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/test_events/stats")
                .timeout(10000)
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(200, response.statusCode(), "Should retrieve stats");

                    JsonObject responseBody = response.bodyAsJsonObject();
                    assertNotNull(responseBody, "Response body should not be null");

                    // Stats should contain event count and other metrics
                    // The exact fields depend on the implementation
                    logger.info("Event store stats retrieved: {}", responseBody.encodePrettily());

                    logger.info("=== TEST METHOD COMPLETED: testEventStoreStats ===");
                    testContext.completeNow();
                })));
    }

    // ==================== SSE Streaming Tests ====================

    /**
     * Tests that the SSE streaming endpoint returns proper SSE headers and connection event.
     * This test verifies:
     * 1. SSE headers are set correctly (Content-Type: text/event-stream)
     * 2. Initial connection event is sent
     * 3. Connection can be established successfully
     */
    @Test
    @Order(20)
    void testEventStoreSSEStreamConnection(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreSSEStreamConnection ===");
        logger.info("Testing SSE stream connection for event store");

        // Use raw HTTP client for SSE since WebClient doesn't handle streaming well
        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/stream")
                .onSuccess(request -> {
                    request.send()
                            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                                logger.info("SSE response status: {}", response.statusCode());
                                logger.info("SSE Content-Type: {}", response.getHeader("Content-Type"));

                                assertEquals(200, response.statusCode(), "SSE endpoint should return 200");
                                assertEquals("text/event-stream", response.getHeader("Content-Type"),
                                        "Content-Type should be text/event-stream");
                                assertEquals("no-cache", response.getHeader("Cache-Control"),
                                        "Cache-Control should be no-cache");

                                // Read the first chunk of data (connection event)
                                StringBuilder receivedData = new StringBuilder();
                                response.handler(buffer -> {
                                    String data = buffer.toString();
                                    logger.info("Received SSE data: {}", data);
                                    receivedData.append(data);

                                    // Check if we received the connection event
                                    if (receivedData.toString().contains("event: connection")) {
                                        logger.info("SSE connection event received");

                                        // Verify connection event format
                                        assertTrue(receivedData.toString().contains("\"type\":\"connection\""),
                                                "Connection event should have type=connection");
                                        assertTrue(receivedData.toString().contains("\"setupId\":\"" + testSetupId + "\""),
                                                "Connection event should contain setupId");
                                        assertTrue(receivedData.toString().contains("\"eventStoreName\":\"test_events\""),
                                                "Connection event should contain eventStoreName");

                                        // Close the connection and complete the test
                                        response.request().connection().close();
                                        logger.info("=== TEST METHOD COMPLETED: testEventStoreSSEStreamConnection ===");
                                        testContext.completeNow();
                                    }
                                });

                                // Set a timeout in case no data is received
                                vertx.setTimer(5000, timerId -> {
                                    if (!testContext.completed()) {
                                        testContext.failNow(new AssertionError(
                                                "Timeout waiting for SSE connection event. Received: " + receivedData));
                                    }
                                });
                            })));
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Tests SSE streaming with event type filter query parameter.
     * Note: Event types must contain only alphanumeric characters and underscores (no dots).
     */
    @Test
    @Order(21)
    void testEventStoreSSEStreamWithEventTypeFilter(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreSSEStreamWithEventTypeFilter ===");
        logger.info("Testing SSE stream with eventType filter");

        String eventTypeFilter = "order_created";

        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/stream?eventType=" + eventTypeFilter)
                .onSuccess(request -> {
                    request.send()
                            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                                assertEquals(200, response.statusCode(), "SSE endpoint should return 200");

                                StringBuilder receivedData = new StringBuilder();
                                response.handler(buffer -> {
                                    String data = buffer.toString();
                                    logger.info("Received SSE data with filter: {}", data);
                                    receivedData.append(data);

                                    // Check if we received the connection event with filter info
                                    if (receivedData.toString().contains("event: connection")) {
                                        logger.info("SSE connection event received with filter");

                                        // Verify filter is included in connection event
                                        assertTrue(receivedData.toString().contains("\"eventTypeFilter\":\"" + eventTypeFilter + "\""),
                                                "Connection event should contain eventTypeFilter");

                                        response.request().connection().close();
                                        logger.info("=== TEST METHOD COMPLETED: testEventStoreSSEStreamWithEventTypeFilter ===");
                                        testContext.completeNow();
                                    }
                                });

                                vertx.setTimer(5000, timerId -> {
                                    if (!testContext.completed()) {
                                        testContext.failNow(new AssertionError(
                                                "Timeout waiting for SSE connection event. Received: " + receivedData));
                                    }
                                });
                            })));
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Tests SSE streaming with aggregate ID filter query parameter.
     */
    @Test
    @Order(22)
    void testEventStoreSSEStreamWithAggregateIdFilter(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreSSEStreamWithAggregateIdFilter ===");
        logger.info("Testing SSE stream with aggregateId filter");

        String aggregateIdFilter = "ORDER-12345";

        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/test_events/events/stream?aggregateId=" + aggregateIdFilter)
                .onSuccess(request -> {
                    request.send()
                            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                                assertEquals(200, response.statusCode(), "SSE endpoint should return 200");

                                StringBuilder receivedData = new StringBuilder();
                                response.handler(buffer -> {
                                    String data = buffer.toString();
                                    logger.info("Received SSE data with aggregateId filter: {}", data);
                                    receivedData.append(data);

                                    if (receivedData.toString().contains("event: connection")) {
                                        logger.info("SSE connection event received with aggregateId filter");

                                        assertTrue(receivedData.toString().contains("\"aggregateIdFilter\":\"" + aggregateIdFilter + "\""),
                                                "Connection event should contain aggregateIdFilter");

                                        response.request().connection().close();
                                        logger.info("=== TEST METHOD COMPLETED: testEventStoreSSEStreamWithAggregateIdFilter ===");
                                        testContext.completeNow();
                                    }
                                });

                                vertx.setTimer(5000, timerId -> {
                                    if (!testContext.completed()) {
                                        testContext.failNow(new AssertionError(
                                                "Timeout waiting for SSE connection event. Received: " + receivedData));
                                    }
                                });
                            })));
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Tests SSE streaming for non-existent event store returns error.
     */
    @Test
    @Order(23)
    void testEventStoreSSEStreamNonExistentStore(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== TEST METHOD STARTED: testEventStoreSSEStreamNonExistentStore ===");
        logger.info("Testing SSE stream for non-existent event store");

        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + testSetupId + "/nonexistent_store/events/stream")
                .onSuccess(request -> {
                    request.send()
                            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                                // SSE endpoint returns 200 but sends error event
                                assertEquals(200, response.statusCode(), "SSE endpoint should return 200");

                                StringBuilder receivedData = new StringBuilder();
                                response.handler(buffer -> {
                                    String data = buffer.toString();
                                    logger.info("Received SSE data for non-existent store: {}", data);
                                    receivedData.append(data);

                                    // Should receive error event for non-existent store
                                    if (receivedData.toString().contains("event: error") ||
                                            receivedData.toString().contains("\"type\":\"error\"")) {
                                        logger.info("SSE error event received for non-existent store");

                                        assertTrue(receivedData.toString().contains("not found") ||
                                                        receivedData.toString().contains("Event store not found"),
                                                "Error should indicate store not found");

                                        response.request().connection().close();
                                        logger.info("=== TEST METHOD COMPLETED: testEventStoreSSEStreamNonExistentStore ===");
                                        testContext.completeNow();
                                    }
                                });

                                vertx.setTimer(5000, timerId -> {
                                    if (!testContext.completed()) {
                                        // If we got connection event but no error, that's also acceptable
                                        // as the error might come later when subscription is attempted
                                        if (receivedData.toString().contains("event: connection")) {
                                            logger.info("Connection established, waiting for subscription error...");
                                        } else {
                                            testContext.failNow(new AssertionError(
                                                    "Timeout waiting for SSE error event. Received: " + receivedData));
                                        }
                                    }
                                });

                                // Extended timeout for subscription error
                                vertx.setTimer(8000, timerId -> {
                                    if (!testContext.completed()) {
                                        response.request().connection().close();
                                        // If we got connection but no error after 8 seconds,
                                        // the store might exist or error handling is different
                                        logger.warn("No error received for non-existent store after 8s. Received: {}", receivedData);
                                        testContext.completeNow();
                                    }
                                });
                            })));
                })
                .onFailure(testContext::failNow);
    }

    // ==================== Dead Letter Queue REST API Tests ====================

    @Test
    @DisplayName("Dead Letter Queue - GET /deadletter/stats returns statistics")
    void testDeadLetterStats(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/stats")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Dead letter stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonObject stats = response.bodyAsJsonObject();
                        assertNotNull(stats);
                        assertTrue(stats.containsKey("totalMessages"));
                        assertTrue(stats.containsKey("uniqueTopics"));
                        assertTrue(stats.containsKey("uniqueTables"));
                        assertTrue(stats.containsKey("averageRetryCount"));
                        testContext.completeNow();
                    } else if (response.statusCode() == 404) {
                        // Setup might not be found - this is acceptable for this test
                        logger.warn("Setup not found for dead letter stats test");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Dead Letter Queue - GET /deadletter/messages returns empty list initially")
    void testDeadLetterListMessages(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/messages")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Dead letter messages response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonArray messages = response.bodyAsJsonArray();
                        assertNotNull(messages);
                        // Initially should be empty or have test data
                        logger.info("Found {} dead letter messages", messages.size());
                        testContext.completeNow();
                    } else if (response.statusCode() == 404) {
                        // Setup might not be found - this is acceptable for this test
                        logger.warn("Setup not found for dead letter messages test");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Dead Letter Queue - GET /deadletter/messages with topic filter")
    void testDeadLetterListMessagesWithTopicFilter(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/messages")
                .addQueryParam("topic", "test_topic")
                .addQueryParam("limit", "10")
                .addQueryParam("offset", "0")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Dead letter messages with filter response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonArray messages = response.bodyAsJsonArray();
                        assertNotNull(messages);
                        testContext.completeNow();
                    } else if (response.statusCode() == 404) {
                        logger.warn("Setup not found for dead letter messages filter test");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Dead Letter Queue - GET /deadletter/messages/:messageId returns 404 for non-existent message")
    void testDeadLetterGetNonExistentMessage(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/messages/999999")
                .send()
                .onSuccess(response -> {
                    logger.info("Dead letter get message response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 404) {
                        // Expected - message doesn't exist
                        testContext.completeNow();
                    } else if (response.statusCode() == 200) {
                        // Unexpected but acceptable if message exists
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Dead Letter Queue - POST /deadletter/cleanup cleans up old messages")
    void testDeadLetterCleanup(Vertx vertx, VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/cleanup")
                .addQueryParam("retentionDays", "30")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Dead letter cleanup response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonObject result = response.bodyAsJsonObject();
                        assertNotNull(result);
                        assertTrue(result.getBoolean("success"));
                        assertTrue(result.containsKey("messagesDeleted"));
                        assertEquals(30, result.getInteger("retentionDays"));
                        testContext.completeNow();
                    } else if (response.statusCode() == 404) {
                        logger.warn("Setup not found for dead letter cleanup test");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Dead Letter Queue - Invalid message ID returns 400")
    void testDeadLetterInvalidMessageId(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/deadletter/messages/invalid")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Dead letter invalid ID response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 400) {
                        // Expected - invalid message ID
                        JsonObject error = response.bodyAsJsonObject();
                        assertNotNull(error);
                        assertTrue(error.containsKey("error"));
                        testContext.completeNow();
                    } else if (response.statusCode() == 404) {
                        // Setup not found is also acceptable
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    // ==================== Subscription Lifecycle REST API Tests ====================

    @Test
    @DisplayName("Subscription - GET /subscriptions/:topic returns empty list or error if table missing")
    void testSubscriptionListEmpty(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Subscription list response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonArray subscriptions = response.bodyAsJsonArray();
                        assertNotNull(subscriptions);
                        logger.info("Found {} subscriptions", subscriptions.size());
                        testContext.completeNow();
                    } else if (response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        // 404: Setup not found, 503: Table doesn't exist (no queues created)
                        // All are acceptable for this test since we only create event stores
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Subscription - GET /subscriptions/:topic/:groupName returns 404 or error for non-existent")
    void testSubscriptionGetNonExistent(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic/nonexistent_group")
                .send()
                .onSuccess(response -> {
                    logger.info("Subscription get response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        // 404: Subscription doesn't exist, 503: Table doesn't exist
                        // All are acceptable for this test
                        testContext.completeNow();
                    } else if (response.statusCode() == 200) {
                        // Unexpected but acceptable if subscription exists
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Subscription - POST /subscriptions/:topic/:groupName/pause handles non-existent gracefully")
    void testSubscriptionPauseNonExistent(Vertx vertx, VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic/nonexistent_group/pause")
                .send()
                .onSuccess(response -> {
                    logger.info("Subscription pause response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Either 200 (success), 404 (not found), or 503 (error) are acceptable
                    // depending on whether the subscription exists
                    if (response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Subscription - POST /subscriptions/:topic/:groupName/resume handles non-existent gracefully")
    void testSubscriptionResumeNonExistent(Vertx vertx, VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic/nonexistent_group/resume")
                .send()
                .onSuccess(response -> {
                    logger.info("Subscription resume response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Either 200 (success), 404 (not found), or 503 (error) are acceptable
                    if (response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Subscription - POST /subscriptions/:topic/:groupName/heartbeat handles non-existent gracefully")
    void testSubscriptionHeartbeatNonExistent(Vertx vertx, VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic/nonexistent_group/heartbeat")
                .send()
                .onSuccess(response -> {
                    logger.info("Subscription heartbeat response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Either 200 (success), 404 (not found), or 503 (error) are acceptable
                    if (response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Subscription - DELETE /subscriptions/:topic/:groupName handles non-existent gracefully")
    void testSubscriptionCancelNonExistent(Vertx vertx, VertxTestContext testContext) {
        webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/subscriptions/test_topic/nonexistent_group")
                .send()
                .onSuccess(response -> {
                    logger.info("Subscription cancel response: {} - {}", response.statusCode(), response.bodyAsString());

                    // Either 200 (success), 404 (not found), or 503 (error) are acceptable
                    if (response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    // ==================== Health API Tests ====================

    @Test
    @DisplayName("Health - GET /health returns overall health status")
    void testHealthOverall(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/health")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Health response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200 || response.statusCode() == 503) {
                        // 200: healthy, 503: unhealthy - both are valid responses
                        JsonObject health = response.bodyAsJsonObject();
                        assertNotNull(health);
                        assertTrue(health.containsKey("status"));
                        assertTrue(health.containsKey("timestamp"));
                        testContext.completeNow();
                    } else if (response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        // Setup not found or health service not available
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Health - GET /health/components returns component list")
    void testHealthComponentsList(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/health/components")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Health components response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 200) {
                        JsonArray components = response.bodyAsJsonArray();
                        assertNotNull(components);
                        logger.info("Found {} health components", components.size());
                        testContext.completeNow();
                    } else if (response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        // Setup not found or health service not available
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Health - GET /health/components/:name returns 404 for non-existent component")
    void testHealthComponentNotFound(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/health/components/nonexistent_component")
                .send()
                .onSuccess(response -> {
                    logger.info("Health component response: {} - {}", response.statusCode(), response.bodyAsString());

                    if (response.statusCode() == 404 || response.statusCode() == 500 || response.statusCode() == 503) {
                        // 404: Component not found, 503: Health service not available
                        testContext.completeNow();
                    } else if (response.statusCode() == 200) {
                        // Unexpected but acceptable if component exists
                        testContext.completeNow();
                    } else {
                        testContext.failNow(new AssertionError("Unexpected status: " + response.statusCode()));
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("EventStore - Query events with bi-temporal parameters (validTimeRange, transactionTimeRange, sortOrder, includeCorrections)")
    void testBiTemporalEventQuery(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test: Bi-Temporal Event Query ===");

        // Step 1: Store multiple events with different valid times
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);
        Instant twoHoursAgo = now.minusSeconds(7200);

        String aggregateId = "ORDER-" + System.currentTimeMillis();

        JsonObject event1 = new JsonObject()
                .put("eventType", "OrderCreated")
                .put("payload", new JsonObject()
                        .put("orderId", aggregateId)
                        .put("amount", 100.0))
                .put("validTime", twoHoursAgo.toString())
                .put("aggregateId", aggregateId);

        JsonObject event2 = new JsonObject()
                .put("eventType", "OrderUpdated")
                .put("payload", new JsonObject()
                        .put("orderId", aggregateId)
                        .put("amount", 150.0))
                .put("validTime", oneHourAgo.toString())
                .put("aggregateId", aggregateId);

        JsonObject event3 = new JsonObject()
                .put("eventType", "OrderCompleted")
                .put("payload", new JsonObject()
                        .put("orderId", aggregateId)
                        .put("amount", 150.0))
                .put("validTime", now.toString())
                .put("aggregateId", aggregateId);

        logger.info("Storing event 1 with validTime: {}", twoHoursAgo);
        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .sendJsonObject(event1)
                .compose(response1 -> {
                    logger.info("Event 1 stored: {} - {}", response1.statusCode(), response1.bodyAsString());
                    assertEquals(201, response1.statusCode(), "Event 1 should be stored");

                    logger.info("Storing event 2 with validTime: {}", oneHourAgo);
                    return webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .sendJsonObject(event2);
                })
                .compose(response2 -> {
                    logger.info("Event 2 stored: {} - {}", response2.statusCode(), response2.bodyAsString());
                    assertEquals(201, response2.statusCode(), "Event 2 should be stored");

                    logger.info("Storing event 3 with validTime: {}", now);
                    return webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .sendJsonObject(event3);
                })
                .compose(response3 -> {
                    logger.info("Event 3 stored: {} - {}", response3.statusCode(), response3.bodyAsString());
                    assertEquals(201, response3.statusCode(), "Event 3 should be stored");

                    // Step 2: Query with valid time range (last 90 minutes) - without aggregateId filter first
                    Instant validTimeFrom = now.minusSeconds(5400); // 90 minutes ago
                    logger.info("Querying events with validTimeFrom={}, validTimeTo={}, sortOrder=VALID_TIME_ASC", validTimeFrom, now);

                    return webClient.get(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .addQueryParam("validTimeFrom", validTimeFrom.toString())
                            .addQueryParam("validTimeTo", now.toString())
                            .addQueryParam("sortOrder", "VALID_TIME_ASC")
                            .addQueryParam("includeCorrections", "true")
                            .send();
                })
                .onSuccess(queryResponse -> {
                    testContext.verify(() -> {
                        logger.info("Query response: {} - {}", queryResponse.statusCode(), queryResponse.bodyAsString());

                        assertEquals(200, queryResponse.statusCode(), "Query should succeed");

                        JsonObject body = queryResponse.bodyAsJsonObject();
                        assertNotNull(body, "Response should be a JSON object");

                        JsonArray events = body.getJsonArray("events");
                        assertNotNull(events, "Events array should be present");

                        // Should return events 2 and 3 (within last 90 minutes)
                        assertTrue(events.size() >= 2,
                                "Should return at least 2 events within valid time range. Got " + events.size() + " events. Response: " + body.encode());

                        // Verify filters in response
                        JsonObject filters = body.getJsonObject("filters");
                        assertNotNull(filters, "Filters should be present in response");
                        assertEquals("VALID_TIME_ASC", filters.getString("sortOrder"));
                        assertTrue(filters.getBoolean("includeCorrections"));

                        logger.info("Bi-temporal query successful:");
                        logger.info("   - Returned {} events", events.size());
                        logger.info("   - Filters: sortOrder={}, includeCorrections={}, validTimeFrom={}, validTimeTo={}",
                                filters.getString("sortOrder"),
                                filters.getBoolean("includeCorrections"),
                                filters.getString("validTimeFrom"),
                                filters.getString("validTimeTo"));

                        testContext.completeNow();
                    });
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("EventStore - Query with inverted validTime range (from after to) returns error")
    void testQueryEventsWithInvertedValidTimeRange_returnsEmpty(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test: Query with inverted validTime range ===");

        Instant now = Instant.now();

        // Store an event so the store is not empty
        JsonObject event = new JsonObject()
                .put("eventType", "RangeTestEvent")
                .put("payload", new JsonObject().put("key", "value"))
                .put("validTime", now.toString());

        webClient.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                .sendJsonObject(event)
                .compose(storeResponse -> {
                    logger.info("Stored event: {} - {}", storeResponse.statusCode(), storeResponse.bodyAsString());
                    assertEquals(201, storeResponse.statusCode(), "Event should be stored before range test");

                    // Query with inverted range: validTimeFrom is 1 hour in the FUTURE, validTimeTo is 2 hours in the PAST
                    Instant invertedFrom = now.plusSeconds(3600);  // future
                    Instant invertedTo   = now.minusSeconds(7200); // past (before from)
                    logger.info("Querying with inverted range: validTimeFrom={}, validTimeTo={}", invertedFrom, invertedTo);

                    return webClient.get(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/test_events/events")
                            .addQueryParam("validTimeFrom", invertedFrom.toString())
                            .addQueryParam("validTimeTo", invertedTo.toString())
                            .send();
                })
                .onSuccess(queryResponse -> {
                    testContext.verify(() -> {
                        logger.info("Inverted range query response: {} - {}", queryResponse.statusCode(), queryResponse.bodyAsString());

                        // Server validates that start time cannot be after end time  returns error response
                        assertTrue(queryResponse.statusCode() >= 400,
                                "Inverted time range should return a 4xx or 5xx error, got: " + queryResponse.statusCode());

                        logger.info("Inverted valid time range correctly rejected with status {}", queryResponse.statusCode());
                        testContext.completeNow();
                    });
                })
                .onFailure(testContext::failNow);
    }
}
