package dev.mars.peegeeq.integration.bitemporal;

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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Event Visualization Integration Tests")
public class EventVisualizationIntegrationTest extends SmokeTestBase {

    private static final Logger logger = LoggerFactory.getLogger(EventVisualizationIntegrationTest.class);

    private static final String STORE_NAME = "visualization_store";

    @Test
    @DisplayName("Verify Causation Tree Query")
    void testCausationTreeQuery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String correlationId = UUID.randomUUID().toString();

        // 1. Create Setup
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
            .put("eventStoreName", STORE_NAME)
            .put("biTemporalEnabled", true));

        // Single chain with proper error handling
        webClient.post("/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                if (setupResponse.statusCode() != 200 && setupResponse.statusCode() != 201) {
                    return Future.failedFuture("Setup creation failed: " + setupResponse.statusCode() + " " + setupResponse.statusMessage());
                }
                // Root Event
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(new JsonObject()
                        .put("eventType", "order.placed")
                        .put("aggregateId", "order-1")
                        .put("correlationId", correlationId)
                        .put("validTime", Instant.now().toString())
                        .put("eventData", new JsonObject().put("status", "placed")));
            })
            .compose(rootResponse -> {
                if (rootResponse.statusCode() != 200 && rootResponse.statusCode() != 201) {
                    return Future.failedFuture("Root event creation failed: " + rootResponse.statusCode() + " " + rootResponse.statusMessage());
                }
                JsonObject body = rootResponse.bodyAsJsonObject();
                if (body == null) {
                    return Future.failedFuture("Root event response body is null");
                }
                String rootId = body.getString("eventId");
                if (rootId == null) {
                    return Future.failedFuture("Root event ID is null in response: " + body.encode());
                }
                
                // Child Event
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(new JsonObject()
                        .put("eventType", "payment.processed")
                        .put("aggregateId", "payment-1")
                        .put("correlationId", correlationId)
                        .put("causationId", rootId)
                        .put("validTime", Instant.now().toString())
                        .put("eventData", new JsonObject().put("amount", 100)))
                    .map(childResponse -> rootId); // Pass rootId down
            })
            .compose(rootId -> {
                // Query: Find events caused by Root
                return webClient.get( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .addQueryParam("causationId", rootId)
                    .send();
            })
            .compose(response -> {
                assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body must not be null");
                JsonArray events = body.getJsonArray("events");
                assertNotNull(events, "Response must contain 'events' array");
                assertEquals(1, events.size(), "Should find exactly 1 child event");
                assertEquals("payment.processed", events.getJsonObject(0).getString("eventType"));
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Verify Aggregate List Discovery")
    void testAggregateListDiscovery(VertxTestContext testContext) {
        String setupId = generateSetupId();

        // 1. Create Setup
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
            .put("eventStoreName", STORE_NAME)
            .put("biTemporalEnabled", true));

        webClient.post( "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(r -> {
                // 2. Create events for different aggregates
                // Order 1
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(new JsonObject()
                        .put("eventType", "order.placed")
                        .put("aggregateId", "order-A")
                        .put("validTime", Instant.now().toString())
                        .put("eventData", new JsonObject()));
            })
            .compose(r -> {
                // Order 2
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(new JsonObject()
                        .put("eventType", "order.placed")
                        .put("aggregateId", "order-B")
                        .put("validTime", Instant.now().toString())
                        .put("eventData", new JsonObject()));
            })
            .compose(r -> {
                // Payment 1 (Different type)
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(new JsonObject()
                        .put("eventType", "payment.processed")
                        .put("aggregateId", "payment-1")
                        .put("validTime", Instant.now().toString())
                        .put("eventData", new JsonObject()));
            })
            .compose(r -> {
                // 3. Query Unique Aggregates (All)
                return webClient.get( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                    .send();
            })
            .compose(response -> {
                assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                JsonArray aggregates = body.getJsonArray("aggregates");
                assertEquals(3, aggregates.size());
                // Response shape: each element is an AggregateInfo object
                var aggIds = aggregates.stream()
                        .map(o -> ((JsonObject) o).getString("aggregateId"))
                        .toList();
                assertTrue(aggIds.contains("order-A"));
                assertTrue(aggIds.contains("order-B"));
                assertTrue(aggIds.contains("payment-1"));
                // Pagination metadata must be present
                assertEquals(3, body.getInteger("count"));
                assertEquals(3L, body.getLong("totalCount"));
                assertFalse(body.getBoolean("truncated"), "3 results should not be truncated");
                // Each aggregate carries enriched metadata
                aggregates.stream().map(o -> (JsonObject) o).forEach(info -> {
                    assertNotNull(info.getString("aggregateId"));
                    assertTrue(info.getLong("eventCount") > 0, "eventCount must be positive");
                    assertNotNull(info.getJsonArray("eventTypes"), "eventTypes must be present");
                });

                // 4. Query Unique Aggregates (Filtered by Event Type)
                return webClient.get(
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                    .addQueryParam("eventType", "order.placed")
                    .send();
            })
            .compose(response -> {
                assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                JsonArray aggregates = body.getJsonArray("aggregates");

                assertEquals(2, aggregates.size(), "Should only find 2 order aggregates");
                var aggIds = aggregates.stream()
                        .map(o -> ((JsonObject) o).getString("aggregateId"))
                        .toList();
                assertTrue(aggIds.contains("order-A"));
                assertTrue(aggIds.contains("order-B"));
                assertEquals(2L, body.getLong("totalCount"));
                assertFalse(body.getBoolean("truncated"));

                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Aggregate list: limit and offset query params are honoured, truncated flag is correct")
    void testAggregateListPagination(VertxTestContext testContext) {
        String setupId = generateSetupId();

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
                .put("eventStoreName", STORE_NAME)
                .put("biTemporalEnabled", true));

        // Seed 3 events across 3 distinct aggregates
        webClient.post("/api/v1/database-setup/create")
                .sendJsonObject(setupRequest)
                .compose(r -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .sendJsonObject(new JsonObject()
                                .put("eventType", "page.event")
                                .put("aggregateId", "page-agg-1")
                                .put("validTime", Instant.now().toString())
                                .put("eventData", new JsonObject())))
                .compose(r -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .sendJsonObject(new JsonObject()
                                .put("eventType", "page.event")
                                .put("aggregateId", "page-agg-2")
                                .put("validTime", Instant.now().toString())
                                .put("eventData", new JsonObject())))
                .compose(r -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .sendJsonObject(new JsonObject()
                                .put("eventType", "page.event")
                                .put("aggregateId", "page-agg-3")
                                .put("validTime", Instant.now().toString())
                                .put("eventData", new JsonObject())))
                // Page 1: limit=2, offset=0
                .compose(r -> webClient.get("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                        .addQueryParam("limit", "2")
                        .addQueryParam("offset", "0")
                        .send())
                .compose(page1Response -> {
                    assertEquals(200, page1Response.statusCode());
                    JsonObject body = page1Response.bodyAsJsonObject();
                    JsonArray aggs = body.getJsonArray("aggregates");

                    assertEquals(2, aggs.size(), "Page 1 must return exactly 2 aggregates");
                    assertEquals(3L, body.getLong("totalCount"), "totalCount must reflect all 3 aggregates");
                    assertTrue(body.getBoolean("truncated"), "truncated must be true on page 1");
                    assertEquals(2, body.getInteger("limit"), "limit must be echoed in response");
                    assertEquals(0, body.getInteger("offset"), "offset must be echoed in response");

                    var page1Ids = aggs.stream()
                            .map(o -> ((JsonObject) o).getString("aggregateId"))
                            .toList();

                    // Page 2: limit=2, offset=2
                    return webClient.get("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                            .addQueryParam("limit", "2")
                            .addQueryParam("offset", "2")
                            .send()
                            .map(page2Response -> new JsonObject()
                                    .put("page1Ids", new JsonArray(page1Ids))
                                    .put("page2Body", page2Response.bodyAsJsonObject())
                                    .put("page2Status", page2Response.statusCode()));
                })
                .compose(combined -> {
                    assertEquals(200, combined.getInteger("page2Status"));
                    JsonObject page2Body = combined.getJsonObject("page2Body");
                    JsonArray page2Aggs = page2Body.getJsonArray("aggregates");

                    assertEquals(1, page2Aggs.size(), "Page 2 must contain the remaining 1 aggregate");
                    assertEquals(3L, page2Body.getLong("totalCount"), "totalCount must remain 3 on page 2");
                    assertFalse(page2Body.getBoolean("truncated"), "truncated must be false on the last page");
                    assertEquals(2, page2Body.getInteger("offset"), "offset must be echoed as 2 on page 2");

                    // Verify no overlap between the two pages
                    var page1Ids = combined.getJsonArray("page1Ids").stream()
                            .map(Object::toString)
                            .toList();
                    var page2Ids = page2Aggs.stream()
                            .map(o -> ((JsonObject) o).getString("aggregateId"))
                            .toList();
                    page2Ids.forEach(id ->
                            assertFalse(page1Ids.contains(id), "Page 2 must not repeat a page 1 aggregate: " + id));

                    // All 3 aggregate IDs are accounted for across both pages
                    var allIds = new java.util.ArrayList<>(page1Ids);
                    allIds.addAll(page2Ids);
                    assertTrue(allIds.contains("page-agg-1"));
                    assertTrue(allIds.contains("page-agg-2"));
                    assertTrue(allIds.contains("page-agg-3"));

                    return cleanupSetupStrict(setupId);
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Aggregate list: firstEventTime and lastEventTime are populated in REST response")
    void testAggregateEnrichedTemporalMetadata(VertxTestContext testContext) {
        String setupId = generateSetupId();

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
                .put("eventStoreName", STORE_NAME)
                .put("biTemporalEnabled", true));

        // Two events for the same aggregate so eventCount=2 and temporal range is verifiable
        webClient.post("/api/v1/database-setup/create")
                .sendJsonObject(setupRequest)
                .compose(r -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .sendJsonObject(new JsonObject()
                                .put("eventType", "meta.event")
                                .put("aggregateId", "meta-agg-A")
                                .put("validTime", Instant.now().toString())
                                .put("eventData", new JsonObject())))
                .compose(r -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .sendJsonObject(new JsonObject()
                                .put("eventType", "meta.event")
                                .put("aggregateId", "meta-agg-A")
                                .put("validTime", Instant.now().toString())
                                .put("eventData", new JsonObject())))
                .compose(r -> webClient.get("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                        .send())
                .compose(response -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray aggs = body.getJsonArray("aggregates");
                    assertEquals(1, aggs.size(), "Only one distinct aggregate should be present");

                    JsonObject info = aggs.getJsonObject(0);
                    assertEquals("meta-agg-A", info.getString("aggregateId"));
                    assertEquals(2L, info.getLong("eventCount"), "Both events must be counted");

                    String firstEventTime = info.getString("firstEventTime");
                    String lastEventTime  = info.getString("lastEventTime");
                    assertNotNull(firstEventTime, "firstEventTime must be present in REST response");
                    assertNotNull(lastEventTime,  "lastEventTime must be present in REST response");
                    // Both must be parseable ISO-8601 timestamps
                    Instant first = Instant.parse(firstEventTime);
                    Instant last  = Instant.parse(lastEventTime);
                    assertFalse(first.isAfter(last), "firstEventTime must not be after lastEventTime");

                    JsonArray eventTypes = info.getJsonArray("eventTypes");
                    assertNotNull(eventTypes, "eventTypes must be present");
                    assertTrue(eventTypes.contains("meta.event"), "eventTypes must include 'meta.event'");

                    return cleanupSetupStrict(setupId);
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

}
