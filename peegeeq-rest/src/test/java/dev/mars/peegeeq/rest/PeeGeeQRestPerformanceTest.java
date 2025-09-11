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

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
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

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for PeeGeeQ REST API using TestContainers.
 * 
 * Tests the performance characteristics of the REST API including:
 * - Concurrent database setup operations
 * - High-volume message sending
 * - Concurrent event storage
 * - System throughput under load
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@SuppressWarnings("deprecation") // CompositeFuture.all() deprecation - keeping for compatibility
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("performance")
public class PeeGeeQRestPerformanceTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRestPerformanceTest.class);
    private static final int TEST_PORT = 8082;
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_perf_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(512 * 1024 * 1024L) // 512MB for better performance
            .withReuse(false);
    
    private WebClient client;
    private String testSetupId;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        client = WebClient.create(vertx);
        testSetupId = "perf-test-" + System.currentTimeMillis();
        
        logger.info("Starting performance test with setup ID: {}", testSetupId);
        
        // Deploy the REST server
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT))
            .onSuccess(id -> {
                logger.info("Performance test server deployed successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (client != null) {
            client.close();
        }
        logger.info("Performance test cleanup completed");
        testContext.completeNow();
    }
    
    @Test
    @Order(1)
    void testConcurrentDatabaseSetupCreation(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Concurrent Database Setup Creation ===");
        
        int concurrentSetups = 5;
        List<Future<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < concurrentSetups; i++) {
            String setupId = testSetupId + "_concurrent_" + i;
            JsonObject setupRequest = createPerformanceTestSetupRequest(setupId);
            
            Future<Void> future = client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(60000)
                    .sendJsonObject(setupRequest)
                    .compose(response -> {
                        if (response.statusCode() == 200) {
                            successCount.incrementAndGet();
                            logger.info("Setup {} created successfully", setupId);
                        } else {
                            logger.warn("Setup {} failed with status: {}", setupId, response.statusCode());
                        }
                        return Future.succeededFuture();
                    });
            
            futures.add(future);
        }
        
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Future<?>> rawFutures = (List<Future<?>>) (List<?>) futures;
        Future.join(rawFutures)
            .onSuccess(result -> {
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                logger.info("Concurrent setup creation completed in {} ms", duration);
                logger.info("Successful setups: {}/{}", successCount.get(), concurrentSetups);

                testContext.verify(() -> {
                    assertTrue(successCount.get() >= concurrentSetups / 2,
                            "At least half of concurrent setups should succeed");
                    assertTrue(duration < 120000, "Should complete within 2 minutes");
                });

                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(2)
    @org.junit.jupiter.api.Disabled("Performance test - disabled for CI environment")
    void testHighVolumeMessageSending(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing High Volume Message Sending ===");
        
        // First create a setup with queue
        JsonObject setupRequest = createPerformanceTestSetupRequestWithQueue();
        
        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(setupRequest)
                .compose(createResponse -> {
                    logger.info("Setup created for message performance test");
                    
                    int messageCount = 100;
                    List<Future<Void>> messageFutures = new ArrayList<>();
                    AtomicInteger messageSuccessCount = new AtomicInteger(0);
                    
                    long startTime = System.currentTimeMillis();
                    
                    for (int i = 0; i < messageCount; i++) {
                        JsonObject messageRequest = new JsonObject()
                                .put("payload", new JsonObject()
                                        .put("messageId", i)
                                        .put("timestamp", System.currentTimeMillis())
                                        .put("data", "Performance test message " + i))
                                .put("priority", i % 10);
                        
                        Future<Void> messageFuture = client.post(TEST_PORT, "localhost", 
                                "/api/v1/queues/" + testSetupId + "/perf_queue/messages")
                                .putHeader("content-type", "application/json")
                                .timeout(30000)
                                .sendJsonObject(messageRequest)
                                .compose(response -> {
                                    if (response.statusCode() == 200) {
                                        messageSuccessCount.incrementAndGet();
                                    }
                                    return Future.succeededFuture();
                                });
                        
                        messageFutures.add(messageFuture);
                    }
                    
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Future<?>> rawMessageFutures = (List<Future<?>>) (List<?>) messageFutures;
                    return Future.join(rawMessageFutures)
                            .map(result -> {
                                long endTime = System.currentTimeMillis();
                                long duration = endTime - startTime;
                                double throughput = (double) messageSuccessCount.get() / (duration / 1000.0);
                                
                                logger.info("Message sending completed in {} ms", duration);
                                logger.info("Successful messages: {}/{}", messageSuccessCount.get(), messageCount);
                                logger.info("Throughput: {:.2f} messages/second", throughput);
                                
                                assertTrue(messageSuccessCount.get() >= messageCount * 0.5,
                                        "At least 50% of messages should be sent successfully");
                                assertTrue(throughput > 1, "Should achieve at least 1 message/second");
                                
                                return null;
                            });
                })
                .onSuccess(result -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(3)
    void testConcurrentEventStorage(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Concurrent Event Storage ===");
        
        // First create a setup with event store
        JsonObject setupRequest = createPerformanceTestSetupRequestWithEventStore();
        
        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(setupRequest)
                .compose(createResponse -> {
                    logger.info("Setup created for event storage performance test");
                    
                    int eventCount = 50;
                    List<Future<Void>> eventFutures = new ArrayList<>();
                    AtomicInteger eventSuccessCount = new AtomicInteger(0);
                    
                    long startTime = System.currentTimeMillis();
                    
                    for (int i = 0; i < eventCount; i++) {
                        JsonObject eventRequest = new JsonObject()
                                .put("eventType", "PerformanceTestEvent")
                                .put("eventData", new JsonObject()
                                        .put("eventId", i)
                                        .put("timestamp", System.currentTimeMillis())
                                        .put("data", "Performance test event " + i))
                                .put("correlationId", "perf-test-" + i);
                        
                        Future<Void> eventFuture = client.post(TEST_PORT, "localhost", 
                                "/api/v1/eventstores/" + testSetupId + "/perf_events/events")
                                .putHeader("content-type", "application/json")
                                .timeout(30000)
                                .sendJsonObject(eventRequest)
                                .compose(response -> {
                                    if (response.statusCode() == 200) {
                                        eventSuccessCount.incrementAndGet();
                                    }
                                    return Future.succeededFuture();
                                });
                        
                        eventFutures.add(eventFuture);
                    }
                    
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Future<?>> rawEventFutures = (List<Future<?>>) (List<?>) eventFutures;
                    return Future.join(rawEventFutures)
                            .map(result -> {
                                long endTime = System.currentTimeMillis();
                                long duration = endTime - startTime;
                                double throughput = (double) eventSuccessCount.get() / (duration / 1000.0);
                                
                                logger.info("Event storage completed in {} ms", duration);
                                logger.info("Successful events: {}/{}", eventSuccessCount.get(), eventCount);
                                logger.info("Throughput: {:.2f} events/second", throughput);
                                
                                assertTrue(eventSuccessCount.get() >= eventCount * 0.9, 
                                        "At least 90% of events should be stored successfully");
                                assertTrue(throughput > 5, "Should achieve at least 5 events/second");
                                
                                return null;
                            });
                })
                .onSuccess(result -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(4)
    @org.junit.jupiter.api.Disabled("Performance test - disabled for CI environment")
    void testSystemThroughputUnderLoad(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing System Throughput Under Load ===");
        
        // Create a complete setup
        JsonObject setupRequest = createCompletePerformanceTestSetupRequest();
        
        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(setupRequest)
                .compose(createResponse -> {
                    logger.info("Complete setup created for throughput test");
                    
                    List<Future<Void>> allOperations = new ArrayList<>();
                    AtomicInteger totalSuccessCount = new AtomicInteger(0);
                    
                    long startTime = System.currentTimeMillis();
                    
                    // Mix of operations: messages, events, and status checks
                    for (int i = 0; i < 30; i++) {
                        // Send message
                        JsonObject messageRequest = new JsonObject()
                                .put("payload", new JsonObject().put("id", i))
                                .put("priority", 5);
                        
                        Future<Void> messageFuture = client.post(TEST_PORT, "localhost", 
                                "/api/v1/queues/" + testSetupId + "/perf_queue/messages")
                                .putHeader("content-type", "application/json")
                                .timeout(30000)
                                .sendJsonObject(messageRequest)
                                .compose(response -> {
                                    if (response.statusCode() == 200) totalSuccessCount.incrementAndGet();
                                    return Future.succeededFuture();
                                });
                        
                        // Store event
                        JsonObject eventRequest = new JsonObject()
                                .put("eventType", "LoadTestEvent")
                                .put("eventData", new JsonObject().put("id", i));
                        
                        Future<Void> eventFuture = client.post(TEST_PORT, "localhost", 
                                "/api/v1/eventstores/" + testSetupId + "/perf_events/events")
                                .putHeader("content-type", "application/json")
                                .timeout(30000)
                                .sendJsonObject(eventRequest)
                                .compose(response -> {
                                    if (response.statusCode() == 200) totalSuccessCount.incrementAndGet();
                                    return Future.succeededFuture();
                                });
                        
                        // Check status
                        Future<Void> statusFuture = client.get(TEST_PORT, "localhost", 
                                "/api/v1/database-setup/" + testSetupId + "/status")
                                .timeout(10000)
                                .send()
                                .compose(response -> {
                                    if (response.statusCode() == 200) totalSuccessCount.incrementAndGet();
                                    return Future.succeededFuture();
                                });
                        
                        allOperations.add(messageFuture);
                        allOperations.add(eventFuture);
                        allOperations.add(statusFuture);
                    }
                    
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Future<?>> rawAllOperations = (List<Future<?>>) (List<?>) allOperations;
                    return Future.join(rawAllOperations)
                            .map(result -> {
                                long endTime = System.currentTimeMillis();
                                long duration = endTime - startTime;
                                double throughput = (double) totalSuccessCount.get() / (duration / 1000.0);
                                
                                logger.info("Load test completed in {} ms", duration);
                                logger.info("Total successful operations: {}/{}", totalSuccessCount.get(), allOperations.size());
                                logger.info("Overall throughput: {:.2f} operations/second", throughput);
                                
                                assertTrue(totalSuccessCount.get() >= allOperations.size() * 0.3,
                                        "At least 30% of operations should succeed under load");
                                assertTrue(throughput > 1, "Should maintain at least 1 operation/second under load");
                                
                                return null;
                            });
                })
                .onSuccess(result -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    // Helper methods for creating performance test requests
    
    private JsonObject createPerformanceTestSetupRequest(String setupId) {
        return new JsonObject()
                .put("setupId", setupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "perf_db_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "public"))
                .put("queues", new JsonArray())
                .put("eventStores", new JsonArray())
                .put("additionalProperties", new JsonObject().put("performance", true));
    }
    
    private JsonObject createPerformanceTestSetupRequestWithQueue() {
        JsonObject request = createPerformanceTestSetupRequest(testSetupId);
        
        JsonArray queues = new JsonArray()
                .add(new JsonObject()
                        .put("queueName", "perf_queue")
                        .put("maxRetries", 3)
                        .put("visibilityTimeoutSeconds", 30)
                        .put("deadLetterEnabled", true));
        
        request.put("queues", queues);
        return request;
    }
    
    private JsonObject createPerformanceTestSetupRequestWithEventStore() {
        JsonObject request = createPerformanceTestSetupRequest(testSetupId);
        
        JsonArray eventStores = new JsonArray()
                .add(new JsonObject()
                        .put("eventStoreName", "perf_events")
                        .put("tableName", "perf_events")
                        .put("biTemporalEnabled", true)
                        .put("notificationPrefix", "perf_events_"));
        
        request.put("eventStores", eventStores);
        return request;
    }
    
    private JsonObject createCompletePerformanceTestSetupRequest() {
        JsonObject request = createPerformanceTestSetupRequest(testSetupId);
        
        JsonArray queues = new JsonArray()
                .add(new JsonObject()
                        .put("queueName", "perf_queue")
                        .put("maxRetries", 3)
                        .put("visibilityTimeoutSeconds", 30)
                        .put("deadLetterEnabled", true));
        
        JsonArray eventStores = new JsonArray()
                .add(new JsonObject()
                        .put("eventStoreName", "perf_events")
                        .put("tableName", "perf_events")
                        .put("biTemporalEnabled", true)
                        .put("notificationPrefix", "perf_events_"));
        
        request.put("queues", queues);
        request.put("eventStores", eventStores);
        return request;
    }
}
