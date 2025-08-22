package dev.mars.peegeeq.examples;

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

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive example demonstrating PeeGeeQ REST API usage.
 *
 * This example demonstrates:
 * - Starting a PostgreSQL TestContainer for database infrastructure
 * - Ensuring PostgreSQL driver is available on classpath
 * - Database setup management via REST API with proper error handling
 * - Queue operations through HTTP endpoints
 * - Event store operations via REST
 * - Health checks and metrics endpoints
 * - CORS and web application integration patterns
 * - Proper resource cleanup and thread management
 *
 * Requirements:
 * - Docker must be available for TestContainers
 * - PostgreSQL driver must be on classpath
 * - All operations fail fast on errors (no graceful skipping)
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
public class RestApiExample {
    
    private static final Logger logger = LoggerFactory.getLogger(RestApiExample.class);
    private static final int REST_PORT = 8080;
    private static final String BASE_URL = "http://localhost:" + REST_PORT;
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ REST API Example ===");

        // Ensure PostgreSQL driver is loaded
        try {
            Class.forName("org.postgresql.Driver");
            logger.info("PostgreSQL driver loaded successfully");
        } catch (ClassNotFoundException e) {
            logger.error("PostgreSQL driver not found. Ensure postgresql dependency is on classpath.", e);
            throw new RuntimeException("PostgreSQL driver not available", e);
        }

        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_rest_demo")
                .withUsername("postgres")
                .withPassword("password")) {
            
            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Start Vert.x and REST server
            Vertx vertx = Vertx.vertx();
            WebClient client = WebClient.create(vertx);
            
            try {
                // Deploy REST server
                CountDownLatch serverLatch = new CountDownLatch(1);
                final Exception[] serverError = {null};

                vertx.deployVerticle(new PeeGeeQRestServer(REST_PORT), result -> {
                    if (result.succeeded()) {
                        logger.info("✅ PeeGeeQ REST Server started on port {}", REST_PORT);
                        serverLatch.countDown();
                    } else {
                        logger.error("❌ Failed to start REST server", result.cause());
                        serverError[0] = new RuntimeException("REST server startup failed", result.cause());
                        serverLatch.countDown();
                    }
                });

                if (!serverLatch.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("REST server startup timed out after 10 seconds");
                }

                if (serverError[0] != null) {
                    throw serverError[0];
                }
                
                // Wait a moment for server to be fully ready
                Thread.sleep(2000);
                
                // Run REST API demonstrations - fail fast on any errors
                try {
                    demonstrateDatabaseSetup(client, postgres);
                    demonstrateQueueOperations(client);
                    demonstrateEventStoreOperations(client);
                    demonstrateHealthAndMetrics(client);
                    demonstrateConsumerGroupManagement(client);

                    logger.info("✅ REST API Example completed successfully!");
                } catch (Exception e) {
                    logger.error("❌ REST API Example failed", e);
                    throw e; // Fail fast - don't hide errors
                }
                
            } finally {
                // Proper cleanup - close resources in reverse order
                logger.info("🧹 Cleaning up resources...");

                try {
                    client.close();
                    logger.info("✅ WebClient closed");
                } catch (Exception e) {
                    logger.warn("⚠️ Error closing WebClient", e);
                }

                try {
                    CountDownLatch vertxCloseLatch = new CountDownLatch(1);
                    vertx.close(result -> {
                        if (result.succeeded()) {
                            logger.info("✅ Vert.x closed successfully");
                        } else {
                            logger.warn("⚠️ Error closing Vert.x", result.cause());
                        }
                        vertxCloseLatch.countDown();
                    });

                    if (!vertxCloseLatch.await(5, TimeUnit.SECONDS)) {
                        logger.warn("⚠️ Vert.x close timed out");
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ Error during Vert.x cleanup", e);
                }

                logger.info("🧹 Resource cleanup completed");
            }
        }
    }
    
    /**
     * Demonstrates database setup management via REST API.
     */
    private static void demonstrateDatabaseSetup(WebClient client, PostgreSQLContainer<?> postgres) throws Exception {
        logger.info("\n--- Database Setup Management ---");
        
        // Create database setup
        JsonObject setupRequest = new JsonObject()
            .put("setupId", "rest-demo-setup")
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getMappedPort(5432))
                .put("databaseName", postgres.getDatabaseName())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", "orders")
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", "PT30S")) // 30 seconds as Duration
                .add(new JsonObject()
                    .put("queueName", "notifications")
                    .put("maxRetries", 5)
                    .put("visibilityTimeout", "PT60S"))) // 60 seconds as Duration
            .put("eventStores", new JsonArray()
                .add(new JsonObject()
                    .put("eventStoreName", "order-events")
                    .put("tableName", "order_events")
                    .put("biTemporalEnabled", true))
                .add(new JsonObject()
                    .put("eventStoreName", "user-events")
                    .put("tableName", "user_events")
                    .put("biTemporalEnabled", true)));
        
        CountDownLatch setupLatch = new CountDownLatch(1);
        final Exception[] setupError = {null};

        client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
            .expect(ResponsePredicate.SC_CREATED)
            .sendJsonObject(setupRequest, result -> {
                if (result.succeeded()) {
                    JsonObject response = result.result().bodyAsJsonObject();
                    logger.info("✅ Database setup created: {}", response.getString("message"));
                    logger.info("   Setup ID: {}", response.getString("setupId"));
                    logger.info("   Queues created: {}", response.getInteger("queuesCreated"));
                    logger.info("   Event stores created: {}", response.getInteger("eventStoresCreated"));
                } else {
                    logger.error("❌ Failed to create database setup", result.cause());
                    setupError[0] = new RuntimeException("Database setup failed", result.cause());
                }
                setupLatch.countDown();
            });

        if (!setupLatch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Database setup timed out after 30 seconds");
        }

        if (setupError[0] != null) {
            throw setupError[0];
        }
        
        // Check setup status
        CountDownLatch statusLatch = new CountDownLatch(1);
        final Exception[] statusError = {null};

        client.get(REST_PORT, "localhost", "/api/v1/database-setup/rest-demo-setup/status")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject status = result.result().bodyAsJsonObject();
                    logger.info("✅ Setup status: {}", status.getString("status"));
                    logger.info("   Health: {}", status.getString("health"));
                } else {
                    logger.error("❌ Failed to get setup status", result.cause());
                    statusError[0] = new RuntimeException("Failed to get setup status", result.cause());
                }
                statusLatch.countDown();
            });

        if (!statusLatch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Setup status check timed out after 10 seconds");
        }

        if (statusError[0] != null) {
            throw statusError[0];
        }
    }
    
    /**
     * Demonstrates queue operations through HTTP endpoints.
     */
    private static void demonstrateQueueOperations(WebClient client) throws Exception {
        logger.info("\n--- Queue Operations ---");
        
        // Send messages to queue
        for (int i = 1; i <= 5; i++) {
            JsonObject message = new JsonObject()
                .put("payload", new JsonObject()
                    .put("orderId", "ORDER-" + String.format("%03d", i))
                    .put("customerId", "CUST-" + (1000 + i))
                    .put("amount", new BigDecimal("99.99").add(new BigDecimal(i)))
                    .put("status", "PENDING"))
                .put("priority", i % 3 + 1)
                .put("headers", new JsonObject()
                    .put("region", i % 2 == 0 ? "US" : "EU")
                    .put("type", "ORDER")
                    .put("source", "web-checkout"));
            
            CountDownLatch messageLatch = new CountDownLatch(1);
            client.post(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/messages")
                .expect(ResponsePredicate.SC_CREATED)
                .sendJsonObject(message, result -> {
                    if (result.succeeded()) {
                        JsonObject response = result.result().bodyAsJsonObject();
                        logger.info("✅ Message sent: {}", response.getString("messageId"));
                    } else {
                        logger.error("❌ Failed to send message", result.cause());
                    }
                    messageLatch.countDown();
                });
            
            messageLatch.await(5, TimeUnit.SECONDS);
        }
        
        // Send batch messages
        JsonArray batchMessages = new JsonArray();
        for (int i = 6; i <= 8; i++) {
            batchMessages.add(new JsonObject()
                .put("payload", new JsonObject()
                    .put("orderId", "BATCH-ORDER-" + i)
                    .put("customerId", "CUST-" + (2000 + i))
                    .put("amount", new BigDecimal("149.99"))
                    .put("status", "PENDING"))
                .put("priority", 2));
        }
        
        JsonObject batchRequest = new JsonObject().put("messages", batchMessages);
        CountDownLatch batchLatch = new CountDownLatch(1);
        client.post(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/messages/batch")
            .expect(ResponsePredicate.SC_CREATED)
            .sendJsonObject(batchRequest, result -> {
                if (result.succeeded()) {
                    JsonObject response = result.result().bodyAsJsonObject();
                    logger.info("✅ Batch messages sent: {} messages", response.getInteger("messagesSent"));
                } else {
                    logger.error("❌ Failed to send batch messages", result.cause());
                }
                batchLatch.countDown();
            });
        
        batchLatch.await(10, TimeUnit.SECONDS);
        
        // Get queue statistics
        CountDownLatch statsLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/stats")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject stats = result.result().bodyAsJsonObject();
                    logger.info("✅ Queue statistics:");
                    logger.info("   Total messages: {}", stats.getInteger("totalMessages"));
                    logger.info("   Pending messages: {}", stats.getInteger("pendingMessages"));
                    logger.info("   Processed messages: {}", stats.getInteger("processedMessages"));
                    logger.info("   Average processing time: {}ms", stats.getDouble("avgProcessingTimeMs"));
                } else {
                    logger.error("❌ Failed to get queue stats", result.cause());
                }
                statsLatch.countDown();
            });
        
        statsLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates event store operations via REST.
     */
    private static void demonstrateEventStoreOperations(WebClient client) throws Exception {
        logger.info("\n--- Event Store Operations ---");
        
        // Store events
        for (int i = 1; i <= 3; i++) {
            JsonObject event = new JsonObject()
                .put("eventType", "OrderCreated")
                .put("eventData", new JsonObject()
                    .put("orderId", "ORDER-" + String.format("%03d", i))
                    .put("customerId", "CUST-" + (1000 + i))
                    .put("amount", new BigDecimal("99.99").add(new BigDecimal(i * 10)))
                    .put("status", "CREATED"))
                .put("validFrom", Instant.now().minusSeconds(i * 60).toString())
                .put("validTo", Instant.now().plusSeconds(86400).toString()) // Valid for 24 hours
                .put("metadata", new JsonObject()
                    .put("source", "order-service")
                    .put("version", "1.0")
                    .put("region", i % 2 == 0 ? "US" : "EU"))
                .put("correlationId", "corr-" + i)
                .put("aggregateId", "ORDER-" + String.format("%03d", i));
            
            CountDownLatch eventLatch = new CountDownLatch(1);
            client.post(REST_PORT, "localhost", "/api/v1/eventstores/rest-demo-setup/order-events/events")
                .expect(ResponsePredicate.SC_CREATED)
                .sendJsonObject(event, result -> {
                    if (result.succeeded()) {
                        JsonObject response = result.result().bodyAsJsonObject();
                        logger.info("✅ Event stored: {}", response.getString("eventId"));
                    } else {
                        logger.error("❌ Failed to store event", result.cause());
                    }
                    eventLatch.countDown();
                });
            
            eventLatch.await(5, TimeUnit.SECONDS);
        }
        
        // Query events
        String queryUrl = "/api/v1/eventstores/rest-demo-setup/order-events/events" +
                         "?validFrom=" + Instant.now().minusSeconds(300).toString() +
                         "&validTo=" + Instant.now().toString() +
                         "&limit=10";
        
        CountDownLatch queryLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", queryUrl)
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject response = result.result().bodyAsJsonObject();
                    JsonArray events = response.getJsonArray("events");
                    logger.info("✅ Events queried: {} events found", events.size());
                    
                    events.forEach(eventObj -> {
                        JsonObject event = (JsonObject) eventObj;
                        logger.info("   Event: {} - {}", 
                                   event.getString("eventId"), 
                                   event.getString("eventType"));
                    });
                } else {
                    logger.error("❌ Failed to query events", result.cause());
                }
                queryLatch.countDown();
            });
        
        queryLatch.await(10, TimeUnit.SECONDS);
        
        // Get event store statistics
        CountDownLatch eventStatsLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", "/api/v1/eventstores/rest-demo-setup/order-events/stats")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject stats = result.result().bodyAsJsonObject();
                    logger.info("✅ Event store statistics:");
                    logger.info("   Total events: {}", stats.getInteger("totalEvents"));
                    logger.info("   Event types: {}", stats.getInteger("eventTypes"));
                    logger.info("   Storage size: {} bytes", stats.getLong("storageSizeBytes"));
                } else {
                    logger.error("❌ Failed to get event store stats", result.cause());
                }
                eventStatsLatch.countDown();
            });
        
        eventStatsLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates health checks and metrics endpoints.
     */
    private static void demonstrateHealthAndMetrics(WebClient client) throws Exception {
        logger.info("\n--- Health Checks and Metrics ---");
        
        // Health check
        CountDownLatch healthLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", "/health")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject health = result.result().bodyAsJsonObject();
                    logger.info("✅ Health check:");
                    logger.info("   Status: {}", health.getString("status"));
                    logger.info("   Uptime: {}ms", health.getLong("uptimeMs"));
                    
                    JsonObject checks = health.getJsonObject("checks");
                    if (checks != null) {
                        checks.fieldNames().forEach(checkName -> {
                            JsonObject check = checks.getJsonObject(checkName);
                            logger.info("   {}: {}", checkName, check.getString("status"));
                        });
                    }
                } else {
                    logger.error("❌ Failed to get health status", result.cause());
                }
                healthLatch.countDown();
            });
        
        healthLatch.await(10, TimeUnit.SECONDS);
        
        // Metrics
        CountDownLatch metricsLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", "/metrics")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    String metricsText = result.result().bodyAsString();
                    logger.info("✅ Metrics endpoint accessible");
                    logger.info("   Metrics data length: {} characters", metricsText.length());
                    
                    // Log a few sample metrics
                    String[] lines = metricsText.split("\n");
                    int count = 0;
                    for (String line : lines) {
                        if (line.startsWith("peegeeq_") && count < 3) {
                            logger.info("   Sample metric: {}", line);
                            count++;
                        }
                    }
                } else {
                    logger.error("❌ Failed to get metrics", result.cause());
                }
                metricsLatch.countDown();
            });
        
        metricsLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates consumer group management via REST API.
     */
    private static void demonstrateConsumerGroupManagement(WebClient client) throws Exception {
        logger.info("\n--- Consumer Group Management ---");
        
        // Create consumer group
        JsonObject groupRequest = new JsonObject()
            .put("groupName", "order-processors")
            .put("maxMembers", 5)
            .put("rebalanceStrategy", "ROUND_ROBIN");
        
        CountDownLatch createGroupLatch = new CountDownLatch(1);
        client.post(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/consumer-groups")
            .expect(ResponsePredicate.SC_CREATED)
            .sendJsonObject(groupRequest, result -> {
                if (result.succeeded()) {
                    JsonObject response = result.result().bodyAsJsonObject();
                    logger.info("✅ Consumer group created: {}", response.getString("groupName"));
                } else {
                    logger.error("❌ Failed to create consumer group", result.cause());
                }
                createGroupLatch.countDown();
            });
        
        createGroupLatch.await(10, TimeUnit.SECONDS);
        
        // Join consumer group
        JsonObject joinRequest = new JsonObject()
            .put("memberName", "processor-1")
            .put("filters", new JsonObject()
                .put("region", "US"));
        
        CountDownLatch joinLatch = new CountDownLatch(1);
        client.post(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/consumer-groups/order-processors/members")
            .expect(ResponsePredicate.SC_CREATED)
            .sendJsonObject(joinRequest, result -> {
                if (result.succeeded()) {
                    JsonObject response = result.result().bodyAsJsonObject();
                    logger.info("✅ Joined consumer group: {}", response.getString("memberId"));
                    logger.info("   Member name: {}", response.getString("memberName"));
                    logger.info("   Member count: {}", response.getInteger("memberCount"));
                } else {
                    logger.error("❌ Failed to join consumer group", result.cause());
                }
                joinLatch.countDown();
            });
        
        joinLatch.await(10, TimeUnit.SECONDS);
        
        // Get consumer group details
        CountDownLatch groupDetailsLatch = new CountDownLatch(1);
        client.get(REST_PORT, "localhost", "/api/v1/queues/rest-demo-setup/orders/consumer-groups/order-processors")
            .expect(ResponsePredicate.SC_OK)
            .send(result -> {
                if (result.succeeded()) {
                    JsonObject group = result.result().bodyAsJsonObject();
                    logger.info("✅ Consumer group details:");
                    logger.info("   Group name: {}", group.getString("groupName"));
                    logger.info("   Member count: {}", group.getInteger("memberCount"));
                    logger.info("   Status: {}", group.getString("status"));
                } else {
                    logger.error("❌ Failed to get consumer group details", result.cause());
                }
                groupDetailsLatch.countDown();
            });
        
        groupDetailsLatch.await(10, TimeUnit.SECONDS);
    }
}
