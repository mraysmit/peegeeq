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

package dev.mars.peegeeq.examples;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating Server-Sent Events (SSE) streaming with PeeGeeQ.
 * 
 * This example shows how to:
 * 1. Connect to SSE streaming endpoint
 * 2. Parse SSE event stream (event: data: format)
 * 3. Handle connection, data, and error events
 * 4. Process messages in real-time
 * 5. Gracefully handle disconnection
 * 
 * Prerequisites:
 * - PeeGeeQ REST server running on localhost:8080
 * - Database setup created (e.g., "demo-setup")
 * - Queue created (e.g., "demo-queue")
 * - Messages being sent to the queue
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-06
 * @version 1.0
 */
public class ServerSentEventsConsumerExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerSentEventsConsumerExample.class);
    
    // Configuration
    private static final String REST_API_HOST = "localhost";
    private static final int REST_API_PORT = 8080;
    private static final String SETUP_ID = "demo-setup";
    private static final String QUEUE_NAME = "demo-queue";
    private static final int MESSAGE_COUNT = 10; // Number of messages to receive before closing
    
    public static void main(String[] args) {
        logger.info("=== Server-Sent Events Consumer Example ===");
        logger.info("Connecting to PeeGeeQ REST API at {}:{}", REST_API_HOST, REST_API_PORT);
        logger.info("Setup: {}, Queue: {}", SETUP_ID, QUEUE_NAME);
        
        Vertx vertx = null;
        HttpClient httpClient = null;
        
        try {
            // Initialize Vert.x and HTTP client
            vertx = Vertx.vertx();
            httpClient = vertx.createHttpClient();
            
            // Demonstrate SSE streaming
            demonstrateSSEStreaming(vertx, httpClient);
            
            logger.info("✅ SSE Consumer Example completed successfully");
            
        } catch (Exception e) {
            logger.error("❌ Error in SSE Consumer Example", e);
        } finally {
            // Cleanup resources
            if (httpClient != null) {
                httpClient.close();
            }
            if (vertx != null) {
                vertx.close();
            }
        }
    }
    
    /**
     * Demonstrates SSE streaming for real-time message consumption.
     */
    private static void demonstrateSSEStreaming(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- SSE Streaming Demo ---");
        
        // Counters for tracking
        AtomicInteger messagesReceived = new AtomicInteger(0);
        CountDownLatch completionLatch = new CountDownLatch(1);
        
        // Build SSE endpoint URL
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + "/stream";
        
        logger.info("📡 Connecting to SSE endpoint: {}", sseUrl);
        
        // Connect to SSE endpoint
        httpClient.request(HttpMethod.GET, REST_API_PORT, REST_API_HOST, sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                logger.info("✅ SSE connection established");
                logger.info("   Status: {}", response.statusCode());
                logger.info("   Content-Type: {}", response.getHeader("Content-Type"));
                
                // Handle SSE data stream
                response.handler(buffer -> {
                    String data = buffer.toString();
                    
                    // Parse SSE events
                    parseSSEEvents(data, messagesReceived);
                    
                    // Check if we've received enough messages
                    if (messagesReceived.get() >= MESSAGE_COUNT) {
                        logger.info("✅ Received {} messages, closing connection", messagesReceived.get());
                        response.request().connection().close();
                        completionLatch.countDown();
                    }
                });
                
                // Handle connection close
                response.endHandler(v -> {
                    logger.info("📡 SSE connection closed");
                    completionLatch.countDown();
                });
                
                // Handle errors
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        logger.error("❌ SSE connection error: {}", err.getMessage());
                    }
                    completionLatch.countDown();
                });
            })
            .onFailure(err -> {
                logger.error("❌ Failed to connect to SSE endpoint: {}", err.getMessage());
                completionLatch.countDown();
            });
        
        // Wait for completion (with timeout)
        boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("⚠️ SSE streaming timed out after 60 seconds");
        }
        
        logger.info("📊 Final Statistics:");
        logger.info("   Messages Received: {}", messagesReceived.get());
    }
    
    /**
     * Parses SSE event stream and handles different event types.
     * 
     * SSE format:
     * event: <event-type>
     * id: <message-id>
     * data: <json-data>
     * 
     * (blank line)
     */
    private static void parseSSEEvents(String data, AtomicInteger messagesReceived) {
        // SSE events are separated by blank lines
        String[] lines = data.split("\n");
        
        String eventType = null;
        String eventId = null;
        StringBuilder eventData = new StringBuilder();
        
        for (String line : lines) {
            if (line.startsWith("event: ")) {
                eventType = line.substring(7).trim();
            } else if (line.startsWith("id: ")) {
                eventId = line.substring(4).trim();
            } else if (line.startsWith("data: ")) {
                eventData.append(line.substring(6));
            } else if (line.trim().isEmpty() && eventData.length() > 0) {
                // End of event - process it
                processSSEEvent(eventType, eventId, eventData.toString(), messagesReceived);
                
                // Reset for next event
                eventType = null;
                eventId = null;
                eventData = new StringBuilder();
            }
        }
    }
    
    /**
     * Processes a complete SSE event.
     */
    private static void processSSEEvent(String eventType, String eventId, String eventData, 
                                       AtomicInteger messagesReceived) {
        try {
            JsonObject event = new JsonObject(eventData);
            String type = event.getString("type");
            
            switch (type) {
                case "connection":
                    handleConnectionEvent(event);
                    break;
                case "configured":
                    handleConfiguredEvent(event);
                    break;
                case "data":
                    handleDataEvent(event, messagesReceived);
                    break;
                case "error":
                    handleErrorEvent(event);
                    break;
                case "heartbeat":
                    handleHeartbeatEvent(event);
                    break;
                default:
                    logger.debug("Unknown event type: {}", type);
            }
        } catch (Exception e) {
            logger.error("Error parsing SSE event: {}", e.getMessage());
        }
    }
    
    /**
     * Handles connection event (sent when SSE connection is established).
     */
    private static void handleConnectionEvent(JsonObject event) {
        String connectionId = event.getString("connectionId");
        String message = event.getString("message");
        logger.info("🔌 Connection Event: {} ({})", message, connectionId);
    }
    
    /**
     * Handles configured event (sent after connection is configured).
     */
    private static void handleConfiguredEvent(JsonObject event) {
        String connectionId = event.getString("connectionId");
        logger.info("⚙️  Configured Event: Connection {} configured", connectionId);
    }
    
    /**
     * Handles data event (actual queue message).
     */
    private static void handleDataEvent(JsonObject event, AtomicInteger messagesReceived) {
        String messageId = event.getString("messageId");
        String messageType = event.getString("messageType");
        Object payload = event.getValue("payload");
        JsonObject headers = event.getJsonObject("headers");
        
        int count = messagesReceived.incrementAndGet();
        
        logger.info("📨 Message #{}: {}", count, messageId);
        logger.info("   Type: {}", messageType);
        logger.info("   Payload: {}", payload);
        if (headers != null && !headers.isEmpty()) {
            logger.info("   Headers: {}", headers.encode());
        }
    }
    
    /**
     * Handles error event.
     */
    private static void handleErrorEvent(JsonObject event) {
        String error = event.getString("error");
        logger.error("❌ Error Event: {}", error);
    }
    
    /**
     * Handles heartbeat event (keep-alive).
     */
    private static void handleHeartbeatEvent(JsonObject event) {
        logger.debug("💓 Heartbeat received");
    }
}

