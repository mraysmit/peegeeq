package dev.mars.peegeeq.integration.bitemporal;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Bi-Temporal Query Smoke Tests")
public class BiTemporalQuerySmokeTest extends SmokeTestBase {

    private static final String STORE_NAME = "bitemporal_smoke_store";

    @Test
    @DisplayName("Verify Point-In-Time (As-Of) Query")
    void testPointInTimeQuery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        
        // 1. Create Setup with Event Store
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
            .put("eventStoreName", STORE_NAME)
            .put("biTemporalEnabled", true));

        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(r -> {
                // 2. Append Event V1 at T1
                JsonObject event1 = new JsonObject()
                    .put("eventType", "TestEvent")
                    .put("aggregateId", "agg-1")
                    .put("validTime", t1.toString())
                    .put("eventData", new JsonObject().put("value", "V1"));
                
                return webClient.post(REST_PORT, REST_HOST, 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(event1);
            })
            .compose(r -> {
                // 3. Append Event V2 at T2
                JsonObject event2 = new JsonObject()
                    .put("eventType", "TestEvent")
                    .put("aggregateId", "agg-1")
                    .put("validTime", t2.toString())
                    .put("eventData", new JsonObject().put("value", "V2"));
                
                return webClient.post(REST_PORT, REST_HOST, 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(event2);
            })
            .compose(r -> {
                // 4. Query with validTime range covering only T1
                // We want events valid at T1.
                // API params: validTimeFrom, validTimeTo
                // Let's query a small window around T1
                Instant t1Start = t1.minus(1, ChronoUnit.MINUTES);
                Instant t1End = t1.plus(1, ChronoUnit.MINUTES);
                
                return webClient.get(REST_PORT, REST_HOST, 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .addQueryParam("aggregateId", "agg-1")
                    .addQueryParam("validTimeFrom", t1Start.toString())
                    .addQueryParam("validTimeTo", t1End.toString())
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                assertEquals(200, response.statusCode());
                JsonObject body = response.bodyAsJsonObject();
                JsonArray events = body.getJsonArray("events");
                
                // Should only find V1
                assertEquals(1, events.size(), "Should find exactly 1 event at T1");
                assertEquals("V1", events.getJsonObject(0).getJsonObject("eventData").getString("value"));
                
                cleanupSetup(setupId);
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Verify Transaction Time (Audit) Query")
    void testTransactionTimeQuery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "dummy_queue");
        setupRequest.getJsonArray("eventStores").add(new JsonObject()
            .put("eventStoreName", STORE_NAME)
            .put("biTemporalEnabled", true));

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(r -> {
                // 1. Append Event
                JsonObject event = new JsonObject()
                    .put("eventType", "AuditEvent")
                    .put("aggregateId", "audit-1")
                    .put("eventData", new JsonObject().put("val", "A"));
                return webClient.post(REST_PORT, REST_HOST, 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .sendJsonObject(event);
            })
            .compose(r -> {
                // 2. Query with transaction time
                // Since we just inserted, it should be there.
                // We can verify that transactionTime is present in response
                return webClient.get(REST_PORT, REST_HOST, 
                        "/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                    .addQueryParam("aggregateId", "audit-1")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                assertEquals(200, response.statusCode());
                JsonArray events = response.bodyAsJsonObject().getJsonArray("events");
                assertEquals(1, events.size());
                
                JsonObject event = events.getJsonObject(0);
                assertTrue(event.containsKey("transactionTime"), "Event should have transactionTime");
                
                cleanupSetup(setupId);
                testContext.completeNow();
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
