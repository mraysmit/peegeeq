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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Event Visualization Integration Tests")
public class EventVisualizationIntegrationTest extends SmokeTestBase {

    private static final String STORE_NAME = "visualization_store";

    @Test
    @DisplayName("Verify Causation Tree Query")
    void testCausationTreeQuery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String correlationId = UUID.randomUUID().toString();
        String rootEventId = UUID.randomUUID().toString();
        String childEventId = UUID.randomUUID().toString();

        // 1. Create Setup
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
            .put("eventStoreName", STORE_NAME)
            .put("biTemporalEnabled", true));

        webClient.post( "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(r -> {
                // 2. Append Root Event (Order Placed)
                JsonObject rootEvent = new JsonObject()
                    .put("eventType", "order.placed")
                    .put("aggregateId", "order-1")
                    .put("correlationId", correlationId)
                    .put("validTime", Instant.now().toString())
                    .put("eventData", new JsonObject().put("status", "placed"));
                
                // We need to manually set eventId to link it later, but the API generates it.
                // So we'll capture it from the response.
                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(rootEvent);
            })
            .compose(response -> {
                JsonObject body = response.bodyAsJsonObject();
                String generatedRootId = body.getString("eventId");
                
                // 3. Append Child Event (Payment Processed) linked via CausationID
                JsonObject childEvent = new JsonObject()
                    .put("eventType", "payment.processed")
                    .put("aggregateId", "payment-1")
                    .put("correlationId", correlationId)
                    .put("causationId", generatedRootId) // Link to root
                    .put("validTime", Instant.now().toString())
                    .put("eventData", new JsonObject().put("amount", 100));

                return webClient.post( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(childEvent);
            })
            .compose(response -> {
                JsonObject body = response.bodyAsJsonObject();
                String generatedChildId = body.getString("eventId");

                // 4. Query by CausationID (Find children of Root)
                // We need to find the event where causationId == generatedRootId
                return webClient.get( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .addQueryParam("causationId", response.bodyAsJsonObject().getString("causationId")) // Wait, we need the ROOT ID here
                    .send();
            })
             // Let's redo the chain to capture IDs properly
            .compose(r -> {
                 // Re-querying with the correct logic in a cleaner chain below
                 return io.vertx.core.Future.succeededFuture(); 
            });
            
            // Restarting the chain for clarity and variable capture
            webClient.post( "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(r -> {
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
            .onComplete(testContext.succeeding(response -> {
                assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                if (body == null) {
                    testContext.failNow("Response body is null");
                    return;
                }
                JsonArray events = body.getJsonArray("events");
                if (events == null) {
                    testContext.failNow("Response body does not contain 'events': " + body.encodePrettily());
                    return;
                }
                
                assertEquals(1, events.size(), "Should find exactly 1 child event");
                assertEquals("payment.processed", events.getJsonObject(0).getString("eventType"));
                
                cleanupSetup(setupId);
                testContext.completeNow();
            }));
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
                JsonArray aggregates = response.bodyAsJsonObject().getJsonArray("aggregates");
                assertEquals(3, aggregates.size());
                assertTrue(aggregates.contains("order-A"));
                assertTrue(aggregates.contains("order-B"));
                assertTrue(aggregates.contains("payment-1"));

                // 4. Query Unique Aggregates (Filtered by Event Type)
                return webClient.get( 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/aggregates")
                    .addQueryParam("eventType", "order.placed")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                assertEquals(200, response.statusCode());
                JsonArray aggregates = response.bodyAsJsonObject().getJsonArray("aggregates");
                
                assertEquals(2, aggregates.size(), "Should only find 2 order aggregates");
                assertTrue(aggregates.contains("order-A"));
                assertTrue(aggregates.contains("order-B"));
                
                cleanupSetup(setupId);
                testContext.completeNow();
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete( "/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> System.err.println("Failed to cleanup setup: " + setupId));
    }
}
