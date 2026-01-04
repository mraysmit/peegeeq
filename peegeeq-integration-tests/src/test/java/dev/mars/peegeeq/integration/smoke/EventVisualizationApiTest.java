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

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Event Visualization API Contract Tests")
class EventVisualizationApiTest extends SmokeTestBase {

    private static final String SCHEMA_PATH = "/event-response-schema.json";

    @Test
    @DisplayName("Should return correct event structure for visualization")
    void shouldReturnCorrectEventStructureForVisualization(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String eventStoreName = "viz_store_" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "corr-" + UUID.randomUUID().toString();
        String aggregateId = "agg-" + UUID.randomUUID().toString();

        // Load Schema
        InputStream schemaStream = getClass().getResourceAsStream(SCHEMA_PATH);
        assertNotNull(schemaStream, "Schema file not found at " + SCHEMA_PATH);
        
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(schemaStream);
        ObjectMapper mapper = new ObjectMapper();

        // 1. Create Setup
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", getPostgresHost())
                .put("port", getPostgresPort())
                .put("databaseName", "viz_db_" + System.currentTimeMillis())
                .put("username", getPostgresUsername())
                .put("password", getPostgresPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray());

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(resp -> {
                assertEquals(201, resp.statusCode(), "Setup creation failed");
                
                // 2. Create Event Store
                JsonObject esRequest = new JsonObject()
                    .put("name", eventStoreName)
                    .put("setup", setupId);
                
                return webClient.post(REST_PORT, REST_HOST, "/api/v1/management/event-stores")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(esRequest);
            })
            .compose(resp -> {
                assertEquals(201, resp.statusCode(), "Event Store creation failed");

                // 3. Post Root Event
                JsonObject rootEvent = new JsonObject()
                    .put("eventType", "RootEvent")
                    .put("eventData", new JsonObject().put("msg", "root"))
                    .put("aggregateId", aggregateId)
                    .put("correlationId", correlationId);
                    // causationId is null for root

                return webClient.post(REST_PORT, REST_HOST, "/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(rootEvent);
            })
            .compose(resp -> {
                assertEquals(201, resp.statusCode(), "Root event post failed");
                String rootId = resp.bodyAsJsonObject().getString("eventId");
                assertNotNull(rootId);

                // 4. Post Child Event
                JsonObject childEvent = new JsonObject()
                    .put("eventType", "ChildEvent")
                    .put("eventData", new JsonObject().put("msg", "child"))
                    .put("aggregateId", aggregateId)
                    .put("correlationId", correlationId)
                    .put("causationId", rootId);

                return webClient.post(REST_PORT, REST_HOST, "/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(childEvent);
            })
            .compose(resp -> {
                assertEquals(201, resp.statusCode(), "Child event post failed");
                String childId = resp.bodyAsJsonObject().getString("eventId");
                assertNotNull(childId);

                // 5. Post GrandChild Event
                JsonObject grandChildEvent = new JsonObject()
                    .put("eventType", "GrandChildEvent")
                    .put("eventData", new JsonObject().put("msg", "grandchild"))
                    .put("aggregateId", aggregateId)
                    .put("correlationId", correlationId)
                    .put("causationId", childId);

                return webClient.post(REST_PORT, REST_HOST, "/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(grandChildEvent);
            })
            .compose(resp -> {
                assertEquals(201, resp.statusCode(), "GrandChild event post failed");

                // 6. Fetch Events by Correlation ID
                return webClient.get(REST_PORT, REST_HOST, "/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events")
                    .addQueryParam("correlationId", correlationId)
                    .send();
            })
            .onComplete(testContext.succeeding(resp -> {
                testContext.verify(() -> {
                    assertEquals(200, resp.statusCode(), "Fetch events failed");
                    String responseBody = resp.bodyAsString();
                    
                    // Validate against JSON Schema
                    try {
                        JsonNode jsonNode = mapper.readTree(responseBody);
                        Set<ValidationMessage> errors = schema.validate(jsonNode);
                        
                        if (!errors.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (ValidationMessage error : errors) {
                                sb.append(error.getMessage()).append("\n");
                            }
                            fail("Schema validation failed:\n" + sb.toString());
                        }
                    } catch (Exception e) {
                        fail("Failed to validate schema: " + e.getMessage());
                    }
                    
                    // Additional logic checks (hierarchy)
                    JsonObject body = resp.bodyAsJsonObject();
                    JsonArray events = body.getJsonArray("events");
                    assertEquals(3, events.size(), "Should have 3 events");
                    
                    boolean hasRoot = false;
                    boolean hasChild = false;
                    boolean hasGrandChild = false;
                    
                    for (int i = 0; i < events.size(); i++) {
                        String type = events.getJsonObject(i).getString("eventType");
                        if ("RootEvent".equals(type)) hasRoot = true;
                        if ("ChildEvent".equals(type)) hasChild = true;
                        if ("GrandChildEvent".equals(type)) hasGrandChild = true;
                    }
                    
                    assertTrue(hasRoot && hasChild && hasGrandChild, "Missing event types in response");
                });
                testContext.completeNow();
            }));
    }
}
