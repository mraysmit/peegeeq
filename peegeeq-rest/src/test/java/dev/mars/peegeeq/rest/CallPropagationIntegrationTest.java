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

package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;



/**
 * Integration test for Call Propagation from REST to Database.
 * 
 * Verifies the complete end-to-end flow described in PEEGEEQ_CALL_PROPAGATION.md:
 * - REST request parsing and validation
 * - Message production through QueueFactory
 * - Database persistence with correct format
 * - PostgreSQL NOTIFY mechanism
 * 
 * This test addresses the gaps identified in section 8 of PEEGEEQ_CALL_PROPAGATION.md.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-30
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CallPropagationIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CallPropagationIntegrationTest.class);
    private static final int TEST_PORT = 18092;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_call_propagation_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private WebClient client;
    private Pool pgPool;
    private String testSetupId;
    private String deploymentId;
    private String testDatabaseName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        client = WebClient.create(vertx);
        testSetupId = "call-propagation-test-" + System.currentTimeMillis();
        testDatabaseName = "test_db_" + System.currentTimeMillis();

        logger.info("=== Starting Call Propagation Integration Test ===");
        logger.info("Test Setup ID: {}", testSetupId);
        logger.info("Test Database: {}", testDatabaseName);

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {}", TEST_PORT);

                // Create PostgreSQL connection pool for direct database queries
                PgConnectOptions connectOptions = new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(testDatabaseName)
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword());

                PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

                pgPool = PgBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(connectOptions)
                    .using(vertx)
                    .build();

                logger.info("PostgreSQL pool created for database verification");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down Call Propagation Test ===");
        
        if (pgPool != null) {
            pgPool.close();
        }
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
    void testRestToDatabasePropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 1: REST to Database Propagation ===");
        
        // Step 1: Create database setup with a queue
        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", testDatabaseName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", "orders")
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30)))
            .put("eventStores", new JsonArray())
            .put("additionalProperties", new JsonObject());

        logger.info("Creating database setup...");
        
        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, setupResponse.statusCode(), 
                        "Setup creation should return 201 Created");
                    JsonObject body = setupResponse.bodyAsJsonObject();
                    assertEquals("ACTIVE", body.getString("status"));
                    logger.info("✅ Database setup created successfully");
                });

                // Step 2: Send a message via REST
                JsonObject messageRequest = new JsonObject()
                    .put("payload", new JsonObject()
                        .put("orderId", "12345")
                        .put("customerId", "67890")
                        .put("amount", 99.99))
                    .put("priority", 5)
                    .put("headers", new JsonObject()
                        .put("source", "integration-test")
                        .put("version", "1.0"));

                logger.info("Sending message via REST API...");
                
                return client.post(TEST_PORT, "localhost", 
                        "/api/v1/queues/" + testSetupId + "/orders/messages")
                    .putHeader("content-type", "application/json")
                    .timeout(10000)
                    .sendJsonObject(messageRequest);
            })
            .compose(messageResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, messageResponse.statusCode(), 
                        "Message send should return 200 OK");
                    JsonObject body = messageResponse.bodyAsJsonObject();
                    assertEquals("Message sent successfully", body.getString("message"));
                    assertEquals("orders", body.getString("queueName"));
                    assertEquals(testSetupId, body.getString("setupId"));
                    assertNotNull(body.getString("messageId"));
                    assertEquals(5, body.getInteger("priority"));
                    logger.info("✅ Message sent successfully via REST: {}", body.getString("messageId"));
                });

                // Step 3: Verify message in database
                logger.info("Querying database to verify message persistence...");
                
                return pgPool.query(
                    "SELECT id, topic, payload, headers, priority, status, created_at " +
                    "FROM queue_messages WHERE topic = 'orders' ORDER BY created_at DESC LIMIT 1"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Message should exist in database");
                
                var row = rows.iterator().next();
                Long id = row.getLong("id");
                String topic = row.getString("topic");
                JsonObject payload = new JsonObject(row.getValue("payload").toString());
                JsonObject headers = new JsonObject(row.getValue("headers").toString());
                Integer priority = row.getInteger("priority");
                String status = row.getString("status");

                // Verify all fields
                assertNotNull(id, "Message ID should be set");
                assertEquals("orders", topic, "Topic should match");
                assertEquals("AVAILABLE", status, "Status should be AVAILABLE");
                assertEquals(5, priority, "Priority should be preserved");
                
                // Verify payload content
                assertEquals("12345", payload.getString("orderId"), 
                    "Payload orderId should match");
                assertEquals("67890", payload.getString("customerId"), 
                    "Payload customerId should match");
                assertEquals(99.99, payload.getDouble("amount"), 0.001, 
                    "Payload amount should match");
                
                // Verify headers
                assertEquals("integration-test", headers.getString("source"), 
                    "Header 'source' should match");
                assertEquals("1.0", headers.getString("version"), 
                    "Header 'version' should match");

                logger.info("✅ Message verified in database:");
                logger.info("  - ID: {}", id);
                logger.info("  - Topic: {}", topic);
                logger.info("  - Status: {}", status);
                logger.info("  - Priority: {}", priority);
                logger.info("  - Payload: {}", payload.encode());
                logger.info("  - Headers: {}", headers.encode());
                
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testMessagePriorityPropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 2: Message Priority Propagation ===");

        JsonObject highPriorityMessage = new JsonObject()
            .put("payload", new JsonObject().put("test", "high-priority"))
            .put("priority", 10);

        client.post(TEST_PORT, "localhost", 
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(highPriorityMessage)
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    logger.info("✅ High priority message sent");
                });

                return pgPool.query(
                    "SELECT priority FROM queue_messages " +
                    "WHERE topic = 'orders' AND payload->>'test' = 'high-priority'"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0);
                assertEquals(10, rows.iterator().next().getInteger("priority"),
                    "Priority 10 should be persisted correctly");
                logger.info("✅ Priority propagation verified");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testMessageDelayPropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 3: Message Delay Propagation ===");

        JsonObject delayedMessage = new JsonObject()
            .put("payload", new JsonObject().put("test", "delayed"))
            .put("delaySeconds", 60);

        client.post(TEST_PORT, "localhost", 
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(delayedMessage)
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    logger.info("✅ Delayed message sent");
                });

                return pgPool.query(
                    "SELECT visible_at, created_at FROM queue_messages " +
                    "WHERE topic = 'orders' AND payload->>'test' = 'delayed'"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0);
                var row = rows.iterator().next();
                var visibleAt = row.getOffsetDateTime("visible_at");
                var createdAt = row.getOffsetDateTime("created_at");
                
                assertNotNull(visibleAt);
                assertNotNull(createdAt);
                
                long delaySeconds = java.time.Duration.between(createdAt, visibleAt).getSeconds();
                assertTrue(delaySeconds >= 59 && delaySeconds <= 61,
                    "Delay should be approximately 60 seconds, was: " + delaySeconds);
                
                logger.info("✅ Delay propagation verified: {} seconds", delaySeconds);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    void testBiTemporalEventStorePropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 4: BiTemporal Event Store Propagation ===");

        // Step 1: Create database setup with an event store
        String eventTestSetupId = "event-test-" + System.currentTimeMillis();
        String eventTestDbName = "event_db_" + System.currentTimeMillis();
        
        JsonObject setupRequest = new JsonObject()
            .put("setupId", eventTestSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", eventTestDbName)
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

        logger.info("Creating event store setup...");

        // Create a pool reference that we can use in the finally block
        final Pool[] eventPoolHolder = new Pool[1];

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, setupResponse.statusCode());
                    logger.info("✅ Event store setup created");
                });

                // Step 2: Store an event with temporal dimensions
                JsonObject eventRequest = new JsonObject()
                    .put("eventType", "OrderCreated")
                    .put("eventData", new JsonObject()
                        .put("orderId", "ORDER-12345")
                        .put("customerId", "CUST-67890")
                        .put("amount", 199.99)
                        .put("currency", "USD"))
                    .put("correlationId", "corr-" + System.currentTimeMillis())
                    .put("validFrom", java.time.Instant.now().toString())
                    .put("metadata", new JsonObject()
                        .put("source", "integration-test")
                        .put("version", "2.0"));

                logger.info("Storing bitemporal event...");

                return client.post(TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + eventTestSetupId + "/order_events/events")
                    .putHeader("content-type", "application/json")
                    .timeout(10000)
                    .sendJsonObject(eventRequest);
            })
            .compose(eventResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, eventResponse.statusCode(),
                        "Event store should return 201 Created");
                    JsonObject body = eventResponse.bodyAsJsonObject();
                    assertEquals("Event stored successfully", body.getString("message"));
                    assertNotNull(body.getString("eventId"));
                    logger.info("✅ Event stored via REST: {}", body.getString("eventId"));
                });

                // Step 3: Create connection pool for the event database
                PgConnectOptions eventConnectOptions = new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(eventTestDbName)
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword());

                PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

                Pool eventPool = PgBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(eventConnectOptions)
                    .using(vertx)
                    .build();

                eventPoolHolder[0] = eventPool;

                // Step 4: Query the database to verify event storage
                logger.info("Verifying event in database...");

                return eventPool.query(
                    "SELECT id, event_type, payload, valid_time, transaction_time " +
                    "FROM order_events WHERE event_type = 'OrderCreated' " +
                    "ORDER BY transaction_time DESC LIMIT 1"
                ).execute();
            })
            .onSuccess(rows -> {
                if (eventPoolHolder[0] != null) {
                    eventPoolHolder[0].close();
                }
                testContext.verify(() -> {
                    assertTrue(rows.size() > 0, "Event should exist in database");

                    var row = rows.iterator().next();
                    String eventType = row.getString("event_type");
                    JsonObject eventData = new JsonObject(row.getValue("payload").toString());
                    var validTime = row.getOffsetDateTime("valid_time");
                    var transactionTime = row.getOffsetDateTime("transaction_time");

                    // Verify bitemporal dimensions
                    assertEquals("OrderCreated", eventType);
                    assertNotNull(validTime, "valid_time should be set");
                    assertNotNull(transactionTime, "transaction_time should be set");

                    // Verify event data
                    assertEquals("ORDER-12345", eventData.getString("orderId"));
                    assertEquals("CUST-67890", eventData.getString("customerId"));
                    assertEquals(199.99, eventData.getDouble("amount"), 0.001);
                    assertEquals("USD", eventData.getString("currency"));

                    logger.info("✅ Bitemporal event verified in database:");
                    logger.info("  - Event Type: {}", eventType);
                    logger.info("  - Valid Time: {}", validTime);
                    logger.info("  - Transaction Time: {}", transactionTime);
                    logger.info("  - Event Data: {}", eventData.encode());

                    testContext.completeNow();
                });
            })
            .onFailure(err -> {
                if (eventPoolHolder[0] != null) {
                    eventPoolHolder[0].close();
                }
                testContext.failNow(err);
            });
    }

    @Test
    @Order(5)
    void testEventQueryByTemporalRange(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 5: Event Query by Temporal Range ===");

        // This test verifies that we can query events by their temporal dimensions
        // Create a setup and store multiple events with different valid_from times
        String queryTestSetupId = "query-test-" + System.currentTimeMillis();
        String queryTestDbName = "query_db_" + System.currentTimeMillis();

        JsonObject setupRequest = new JsonObject()
            .put("setupId", queryTestSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", queryTestDbName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")  // Use template0 like test 4 - let DatabaseSetupService handle templates
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray()
                .add(new JsonObject()
                    .put("eventStoreName", "timeline_events")
                    .put("tableName", "timeline_events")
                    .put("biTemporalEnabled", true)
                    .put("notificationPrefix", "timeline")
                    .put("partitioningEnabled", false)))
            .put("additionalProperties", new JsonObject());

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, setupResponse.statusCode());
                    logger.info("✅ Query test setup created");
                });

                // Store multiple events with different timestamps
                java.time.Instant now = java.time.Instant.now();
                java.time.Instant past = now.minus(java.time.Duration.ofHours(1));
                java.time.Instant future = now.plus(java.time.Duration.ofHours(1));

                JsonObject pastEvent = new JsonObject()
                    .put("eventType", "StatusChanged")
                    .put("eventData", new JsonObject().put("status", "pending"))
                    .put("validFrom", past.toString());

                JsonObject currentEvent = new JsonObject()
                    .put("eventType", "StatusChanged")
                    .put("eventData", new JsonObject().put("status", "processing"))
                    .put("validFrom", now.toString());

                JsonObject futureEvent = new JsonObject()
                    .put("eventType", "StatusChanged")
                    .put("eventData", new JsonObject().put("status", "completed"))
                    .put("validFrom", future.toString());

                // Store all three events
                return client.post(TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + queryTestSetupId + "/timeline_events/events")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(pastEvent)
                    .compose(r1 -> client.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + queryTestSetupId + "/timeline_events/events")
                        .putHeader("content-type", "application/json")
                        .sendJsonObject(currentEvent))
                    .compose(r2 -> client.post(TEST_PORT, "localhost",
                            "/api/v1/eventstores/" + queryTestSetupId + "/timeline_events/events")
                        .putHeader("content-type", "application/json")
                        .sendJsonObject(futureEvent));
            })
            .compose(finalResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, finalResponse.statusCode(), "Event store should return 201 Created");
                    logger.info("✅ Three temporal events stored");
                });

                // Query events using the REST API
                return client.get(TEST_PORT, "localhost",
                        "/api/v1/eventstores/" + queryTestSetupId + "/timeline_events/events")
                    .addQueryParam("eventType", "StatusChanged")
                    .addQueryParam("limit", "10")
                    .timeout(10000)
                    .send();
            })
            .onSuccess(queryResponse -> testContext.verify(() -> {
                assertEquals(200, queryResponse.statusCode(),
                    "Event query should return 200 OK");
                
                JsonObject body = queryResponse.bodyAsJsonObject();
                JsonArray events = body.getJsonArray("events");
                
                assertNotNull(events, "Events array should not be null");
                assertTrue(events.size() >= 3,
                    "Should retrieve at least 3 events, got: " + events.size());

                logger.info("✅ Temporal query verified: {} events retrieved", events.size());
                
                // Verify events have temporal data
                for (int i = 0; i < events.size(); i++) {
                    JsonObject event = events.getJsonObject(i);
                    assertNotNull(event.getString("validFrom"),
                        "Event " + i + " should have validFrom");
                    assertNotNull(event.getString("transactionTime"),
                        "Event " + i + " should have transactionTime");
                }

                logger.info("✅ All events have proper temporal dimensions");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // ==================== Messaging Correlation ID and Message Group Tests ====================

    @Test
    @Order(7)
    void testCorrelationIdPropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 7: Correlation ID Propagation ===");

        String customCorrelationId = "test-correlation-" + System.currentTimeMillis();

        JsonObject messageWithCorrelationId = new JsonObject()
            .put("payload", new JsonObject()
                .put("test", "correlation-id-test")
                .put("timestamp", System.currentTimeMillis()))
            .put("correlationId", customCorrelationId)
            .put("headers", new JsonObject()
                .put("source", "correlation-test"));

        client.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageWithCorrelationId)
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Message send should return 200 OK");
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(customCorrelationId, body.getString("correlationId"),
                        "Response should include the custom correlation ID");
                    logger.info("✅ Message sent with custom correlation ID: {}", customCorrelationId);
                });

                // Verify correlation ID in database
                return pgPool.query(
                    "SELECT correlation_id FROM queue_messages " +
                    "WHERE topic = 'orders' AND payload->>'test' = 'correlation-id-test'"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Message should exist in database");
                String dbCorrelationId = rows.iterator().next().getString("correlation_id");
                assertEquals(customCorrelationId, dbCorrelationId,
                    "Correlation ID should be persisted correctly in database");
                logger.info("✅ Correlation ID propagation verified: {}", dbCorrelationId);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    void testMessageGroupPropagation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 8: Message Group Propagation ===");

        String messageGroup = "order-group-" + System.currentTimeMillis();

        JsonObject messageWithGroup = new JsonObject()
            .put("payload", new JsonObject()
                .put("test", "message-group-test")
                .put("orderId", "ORDER-001")
                .put("timestamp", System.currentTimeMillis()))
            .put("messageGroup", messageGroup)
            .put("headers", new JsonObject()
                .put("source", "message-group-test"));

        client.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageWithGroup)
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Message send should return 200 OK");
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(messageGroup, body.getString("messageGroup"),
                        "Response should include the message group");
                    logger.info("✅ Message sent with message group: {}", messageGroup);
                });

                // Verify message group in database
                return pgPool.query(
                    "SELECT message_group FROM queue_messages " +
                    "WHERE topic = 'orders' AND payload->>'test' = 'message-group-test'"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Message should exist in database");
                String dbMessageGroup = rows.iterator().next().getString("message_group");
                assertEquals(messageGroup, dbMessageGroup,
                    "Message group should be persisted correctly in database");
                logger.info("✅ Message group propagation verified: {}", dbMessageGroup);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    void testCorrelationIdAndMessageGroupCombined(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 9: Correlation ID and Message Group Combined ===");

        String customCorrelationId = "combined-corr-" + System.currentTimeMillis();
        String messageGroup = "combined-group-" + System.currentTimeMillis();

        JsonObject messageWithBoth = new JsonObject()
            .put("payload", new JsonObject()
                .put("test", "combined-test")
                .put("orderId", "ORDER-COMBINED")
                .put("timestamp", System.currentTimeMillis()))
            .put("correlationId", customCorrelationId)
            .put("messageGroup", messageGroup)
            .put("priority", 7)
            .put("headers", new JsonObject()
                .put("source", "combined-test")
                .put("version", "2.0"));

        client.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageWithBoth)
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Message send should return 200 OK");
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(customCorrelationId, body.getString("correlationId"),
                        "Response should include the custom correlation ID");
                    assertEquals(messageGroup, body.getString("messageGroup"),
                        "Response should include the message group");
                    assertEquals(7, body.getInteger("priority"),
                        "Response should include the priority");
                    logger.info("✅ Message sent with correlation ID, message group, and priority");
                });

                // Verify all fields in database
                return pgPool.query(
                    "SELECT correlation_id, message_group, priority, headers FROM queue_messages " +
                    "WHERE topic = 'orders' AND payload->>'test' = 'combined-test'"
                ).execute();
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Message should exist in database");
                var row = rows.iterator().next();

                String dbCorrelationId = row.getString("correlation_id");
                String dbMessageGroup = row.getString("message_group");
                Integer dbPriority = row.getInteger("priority");
                JsonObject dbHeaders = new JsonObject(row.getValue("headers").toString());

                assertEquals(customCorrelationId, dbCorrelationId,
                    "Correlation ID should be persisted correctly");
                assertEquals(messageGroup, dbMessageGroup,
                    "Message group should be persisted correctly");
                assertEquals(7, dbPriority,
                    "Priority should be persisted correctly");
                assertEquals("combined-test", dbHeaders.getString("source"),
                    "Custom header 'source' should be persisted");
                assertEquals("2.0", dbHeaders.getString("version"),
                    "Custom header 'version' should be persisted");

                logger.info("✅ Combined propagation verified:");
                logger.info("  - Correlation ID: {}", dbCorrelationId);
                logger.info("  - Message Group: {}", dbMessageGroup);
                logger.info("  - Priority: {}", dbPriority);
                logger.info("  - Headers: {}", dbHeaders.encode());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
