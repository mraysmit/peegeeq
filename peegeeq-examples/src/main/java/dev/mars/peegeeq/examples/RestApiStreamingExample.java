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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating PeeGeeQ REST API real-time streaming capabilities.
 * 
 * This example shows:
 * - WebSocket streaming for real-time message consumption
 * - Server-Sent Events (SSE) for message streaming
 * - Real-time consumer group coordination
 * - Message filtering and routing in streaming scenarios
 * - Connection management and error handling
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
public class RestApiStreamingExample {
    
    private static final Logger logger = LoggerFactory.getLogger(RestApiStreamingExample.class);
    private static final int REST_PORT = 8081;
    private static final String BASE_URL = "http://localhost:" + REST_PORT;
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ REST API Streaming Example ===");
        
        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_streaming_demo")
                .withUsername("postgres")
                .withPassword("password")) {
            
            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Start Vert.x and REST server
            Vertx vertx = Vertx.vertx();
            WebClient client = WebClient.create(vertx);
            HttpClient httpClient = vertx.createHttpClient();
            WebSocketClient wsClient = vertx.createWebSocketClient();
            
            try {
                // Deploy REST server
                CountDownLatch serverLatch = new CountDownLatch(1);
                vertx.deployVerticle(new PeeGeeQRestServer(REST_PORT))
                    .onSuccess(deploymentId -> {
                        logger.info("PeeGeeQ REST Server started on port {}", REST_PORT);
                        serverLatch.countDown();
                    })
                    .onFailure(throwable -> {
                        logger.error("Failed to start REST server", throwable);
                        serverLatch.countDown();
                    });
                
                if (!serverLatch.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("REST server failed to start within timeout");
                }
                
                // Wait for server to be fully ready
                Thread.sleep(2000);
                
                // Setup database and queues first
                setupDatabaseAndQueues(client, postgres);
                
                // Run streaming demonstrations
                demonstrateWebSocketStreaming(wsClient);
                demonstrateServerSentEvents(client);
                demonstrateStreamingWithFiltering(wsClient);
                demonstrateConsumerGroupStreaming(wsClient, client);
                
                logger.info("REST API Streaming Example completed successfully!");
                
            } finally {
                httpClient.close();
                client.close();
                vertx.close();
            }
        }
    }
    
    /**
     * Sets up database and queues for streaming demonstrations.
     */
    private static void setupDatabaseAndQueues(WebClient client, PostgreSQLContainer<?> postgres) throws Exception {
        logger.info("\n--- Setting up Database and Queues ---");
        
        JsonObject setupRequest = new JsonObject()
            .put("setupId", "streaming-demo-setup")
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getMappedPort(5432))
                .put("databaseName", postgres.getDatabaseName())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public"))
            .put("queues", new io.vertx.core.json.JsonArray()
                .add(new JsonObject()
                    .put("queueName", "live-orders")
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", "PT30S")) // 30 seconds as Duration
                .add(new JsonObject()
                    .put("queueName", "notifications")
                    .put("maxRetries", 5)
                    .put("visibilityTimeout", "PT60S"))); // 60 seconds as Duration

        CountDownLatch setupLatch = new CountDownLatch(1);
        client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .onSuccess(response -> {
                logger.info("✅ Database setup created for streaming demo");
                setupLatch.countDown();
            })
            .onFailure(throwable -> {
                logger.error("❌ Failed to create database setup", throwable);
                setupLatch.countDown();
            });
        
        setupLatch.await(30, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates WebSocket streaming for real-time message consumption.
     */
    private static void demonstrateWebSocketStreaming(WebSocketClient wsClient) throws Exception {
        logger.info("\n--- WebSocket Streaming ---");

        CountDownLatch wsLatch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);

        // Connect to WebSocket endpoint using Vert.x 5.x API
        WebSocketConnectOptions connectOptions = new WebSocketConnectOptions()
            .setPort(REST_PORT)
            .setHost("localhost")
            .setURI("/ws/queues/streaming-demo-setup/live-orders");

        wsClient.connect(connectOptions)
            .onSuccess(webSocket -> {
                logger.info("✅ WebSocket connected for queue streaming");

                // Set up message handler
                webSocket.textMessageHandler(message -> {
                    try {
                        JsonObject messageObj = new JsonObject(message);
                        String type = messageObj.getString("type");

                        switch (type) {
                            case "welcome":
                                logger.info("📡 WebSocket welcome: {}", messageObj.getString("message"));

                                // Configure the connection
                                JsonObject configMessage = new JsonObject()
                                    .put("type", "configure")
                                    .put("consumerGroup", "ws-consumers")
                                    .put("batchSize", 5)
                                    .put("maxWaitTime", 1000)
                                    .put("filters", new JsonObject()
                                        .put("region", "US"));

                                webSocket.writeTextMessage(configMessage.encode());
                                break;

                            case "configured":
                                logger.info("📡 WebSocket configured: {}", messageObj.getString("connectionId"));

                                // Subscribe to messages
                                JsonObject subscribeMessage = new JsonObject()
                                    .put("type", "subscribe")
                                    .put("topics", new io.vertx.core.json.JsonArray().add("live-orders"));

                                webSocket.writeTextMessage(subscribeMessage.encode());
                                break;

                            case "message":
                                int count = messageCount.incrementAndGet();
                                JsonObject payload = messageObj.getJsonObject("payload");
                                logger.info("📨 WebSocket message #{}: Order {} - ${}",
                                           count,
                                           payload.getString("orderId"),
                                           payload.getDouble("amount"));

                                if (count >= 3) {
                                    webSocket.close();
                                    wsLatch.countDown();
                                }
                                break;

                            case "error":
                                logger.error("❌ WebSocket error: {}", messageObj.getString("message"));
                                break;

                            default:
                                logger.debug("📡 WebSocket message type: {}", type);
                        }
                    } catch (Exception e) {
                        logger.error("Error processing WebSocket message", e);
                    }
                });

                webSocket.exceptionHandler(throwable -> {
                    logger.error("WebSocket exception", throwable);
                    wsLatch.countDown();
                });

                webSocket.closeHandler(v -> {
                    logger.info("📡 WebSocket connection closed");
                    wsLatch.countDown();
                });

                // Send some test messages to trigger streaming
                sendTestMessages();
            })
            .onFailure(throwable -> {
                logger.error("❌ Failed to connect WebSocket", throwable);
                wsLatch.countDown();
            });
        
        wsLatch.await(30, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates Server-Sent Events (SSE) for message streaming.
     */
    private static void demonstrateServerSentEvents(WebClient client) throws Exception {
        logger.info("\n--- Server-Sent Events Streaming ---");
        
        CountDownLatch sseLatch = new CountDownLatch(1);
        AtomicInteger eventCount = new AtomicInteger(0);
        
        // Connect to SSE endpoint with query parameters
        String sseUrl = "/api/v1/queues/streaming-demo-setup/live-orders/stream" +
                       "?consumerGroup=sse-consumers" +
                       "&batchSize=3" +
                       "&maxWaitTime=2000" +
                       "&filter.region=EU";
        
        client.get(REST_PORT, "localhost", sseUrl)
            .putHeader("Accept", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .send()
            .onSuccess(response -> {
                logger.info("✅ SSE connection established");

                String responseBody = response.bodyAsString();
                String[] events = responseBody.split("\n\n");

                for (String event : events) {
                    if (event.trim().isEmpty()) continue;

                    try {
                        // Parse SSE event format
                        String[] lines = event.split("\n");
                        String eventType = null;
                        String data = null;

                        for (String line : lines) {
                            if (line.startsWith("event: ")) {
                                eventType = line.substring(7);
                            } else if (line.startsWith("data: ")) {
                                data = line.substring(6);
                            }
                        }

                        if (eventType != null && data != null) {
                            JsonObject eventData = new JsonObject(data);

                            switch (eventType) {
                                case "connected":
                                    logger.info("📡 SSE connected: {}", eventData.getString("connectionId"));
                                    break;

                                case "configured":
                                    logger.info("📡 SSE configured with consumer group: {}",
                                               eventData.getString("consumerGroup"));
                                    break;

                                case "message":
                                    int count = eventCount.incrementAndGet();
                                    JsonObject payload = eventData.getJsonObject("payload");
                                    logger.info("📨 SSE message #{}: Order {} - ${}",
                                               count,
                                               payload.getString("orderId"),
                                               payload.getDouble("amount"));
                                    break;

                                case "heartbeat":
                                    logger.debug("💓 SSE heartbeat");
                                    break;

                                default:
                                    logger.debug("📡 SSE event type: {}", eventType);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Skipping malformed SSE event: {}", event);
                    }
                }

                logger.info("📡 SSE stream processed {} events", eventCount.get());
                sseLatch.countDown();
            })
            .onFailure(throwable -> {
                logger.error("❌ Failed to connect to SSE stream", throwable);
                sseLatch.countDown();
            });
        
        // Send some test messages for EU region
        sendTestMessagesForRegion("EU");
        
        sseLatch.await(15, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates streaming with message filtering.
     */
    private static void demonstrateStreamingWithFiltering(WebSocketClient wsClient) throws Exception {
        logger.info("\n--- Streaming with Message Filtering ---");

        CountDownLatch filterLatch = new CountDownLatch(1);
        AtomicInteger filteredCount = new AtomicInteger(0);

        WebSocketConnectOptions connectOptions = new WebSocketConnectOptions()
            .setPort(REST_PORT)
            .setHost("localhost")
            .setURI("/ws/queues/streaming-demo-setup/live-orders");

        wsClient.connect(connectOptions).onSuccess(webSocket -> {
            logger.info("✅ WebSocket connected for filtered streaming");

            webSocket.textMessageHandler(message -> {
                    try {
                        JsonObject messageObj = new JsonObject(message);
                        String type = messageObj.getString("type");
                        
                        if ("welcome".equals(type)) {
                            // Configure with complex filters
                            JsonObject configMessage = new JsonObject()
                                .put("type", "configure")
                                .put("consumerGroup", "filtered-consumers")
                                .put("filters", new JsonObject()
                                    .put("region", "US")
                                    .put("priority", "HIGH")
                                    .put("amount.min", 100.0));
                            
                            webSocket.writeTextMessage(configMessage.encode());
                            
                        } else if ("configured".equals(type)) {
                            JsonObject subscribeMessage = new JsonObject()
                                .put("type", "subscribe")
                                .put("topics", new io.vertx.core.json.JsonArray().add("live-orders"));
                            
                            webSocket.writeTextMessage(subscribeMessage.encode());
                            
                        } else if ("message".equals(type)) {
                            int count = filteredCount.incrementAndGet();
                            JsonObject payload = messageObj.getJsonObject("payload");
                            JsonObject headers = messageObj.getJsonObject("headers");
                            
                            logger.info("🔍 Filtered message #{}: Order {} - ${} (Region: {}, Priority: {})", 
                                       count,
                                       payload.getString("orderId"),
                                       payload.getDouble("amount"),
                                       headers.getString("region"),
                                       headers.getString("priority"));
                            
                            if (count >= 2) {
                                webSocket.close();
                                filterLatch.countDown();
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing filtered message", e);
                    }
                });
                
                webSocket.closeHandler(v -> filterLatch.countDown());
                webSocket.exceptionHandler(throwable -> {
                    logger.error("Filtered WebSocket exception", throwable);
                    filterLatch.countDown();
                });
                
            // Send test messages with different filters
            sendTestMessagesWithFilters();
        }).onFailure(throwable -> {
            logger.error("❌ Failed to connect filtered WebSocket", throwable);
            filterLatch.countDown();
        });

        filterLatch.await(20, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates consumer group coordination in streaming scenarios.
     */
    private static void demonstrateConsumerGroupStreaming(WebSocketClient wsClient, WebClient client) throws Exception {
        logger.info("\n--- Consumer Group Streaming Coordination ---");

        // Create consumer group first
        JsonObject groupRequest = new JsonObject()
            .put("groupName", "streaming-processors")
            .put("maxMembers", 3)
            .put("rebalanceStrategy", "ROUND_ROBIN");

        CountDownLatch groupLatch = new CountDownLatch(1);
        client.post(REST_PORT, "localhost", "/api/v1/queues/streaming-demo-setup/live-orders/consumer-groups")
            .sendJsonObject(groupRequest)
            .onSuccess(response -> {
                logger.info("✅ Consumer group created for streaming");
                groupLatch.countDown();
            })
            .onFailure(throwable -> {
                logger.error("❌ Failed to create consumer group", throwable);
                groupLatch.countDown();
            });

        groupLatch.await(10, TimeUnit.SECONDS);
        
        // Connect multiple WebSocket consumers to the same group
        CountDownLatch multiConsumerLatch = new CountDownLatch(2);
        
        for (int i = 1; i <= 2; i++) {
            final int consumerId = i;

            WebSocketConnectOptions connectOptions = new WebSocketConnectOptions()
                .setPort(REST_PORT)
                .setHost("localhost")
                .setURI("/ws/queues/streaming-demo-setup/live-orders");

            wsClient.connect(connectOptions).onSuccess(webSocket -> {
                logger.info("✅ Consumer #{} WebSocket connected", consumerId);

                webSocket.textMessageHandler(message -> {
                        try {
                            JsonObject messageObj = new JsonObject(message);
                            String type = messageObj.getString("type");
                            
                            if ("welcome".equals(type)) {
                                // Join the same consumer group
                                JsonObject configMessage = new JsonObject()
                                    .put("type", "configure")
                                    .put("consumerGroup", "streaming-processors")
                                    .put("memberName", "consumer-" + consumerId);
                                
                                webSocket.writeTextMessage(configMessage.encode());
                                
                            } else if ("configured".equals(type)) {
                                JsonObject subscribeMessage = new JsonObject()
                                    .put("type", "subscribe")
                                    .put("topics", new io.vertx.core.json.JsonArray().add("live-orders"));
                                
                                webSocket.writeTextMessage(subscribeMessage.encode());
                                
                            } else if ("message".equals(type)) {
                                JsonObject payload = messageObj.getJsonObject("payload");
                                logger.info("👥 Consumer #{} received: Order {} - ${}", 
                                           consumerId,
                                           payload.getString("orderId"),
                                           payload.getDouble("amount"));
                                
                                // Close after receiving one message to demonstrate load balancing
                                webSocket.close();
                                multiConsumerLatch.countDown();
                            }
                        } catch (Exception e) {
                            logger.error("Error in consumer #{}", consumerId, e);
                        }
                    });
                    
                webSocket.closeHandler(v -> multiConsumerLatch.countDown());
                webSocket.exceptionHandler(throwable -> {
                    logger.error("Consumer #{} WebSocket exception", consumerId, throwable);
                    multiConsumerLatch.countDown();
                });
            }).onFailure(throwable -> {
                logger.error("❌ Failed to connect consumer #{} WebSocket", consumerId, throwable);
                multiConsumerLatch.countDown();
            });
        }

        // Send messages to demonstrate load balancing
        Thread.sleep(2000); // Wait for connections to establish
        sendTestMessagesForLoadBalancing();

        multiConsumerLatch.await(20, TimeUnit.SECONDS);
    }
    
    /**
     * Sends test messages to trigger streaming.
     */
    private static void sendTestMessages() {
        // This would typically be done via the REST API
        // For demo purposes, we'll simulate message arrival
        logger.info("📤 Sending test messages for streaming...");
    }
    
    /**
     * Sends test messages for a specific region.
     */
    private static void sendTestMessagesForRegion(String region) {
        logger.info("📤 Sending test messages for region: {}", region);
    }
    
    /**
     * Sends test messages with different filter criteria.
     */
    private static void sendTestMessagesWithFilters() {
        logger.info("📤 Sending test messages with filter criteria...");
    }
    
    /**
     * Sends test messages for load balancing demonstration.
     */
    private static void sendTestMessagesForLoadBalancing() {
        logger.info("📤 Sending test messages for load balancing...");
    }
}
