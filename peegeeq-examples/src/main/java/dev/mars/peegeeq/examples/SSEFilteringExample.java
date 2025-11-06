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
 * Example demonstrating SSE filtering, batching, and consumer groups.
 * 
 * This example shows how to:
 * 1. Filter messages by message type
 * 2. Filter messages by headers
 * 3. Configure batch size for efficient streaming
 * 4. Configure max wait time for batching
 * 5. Use consumer groups for load balancing
 * 
 * Prerequisites:
 * - PeeGeeQ REST server running on localhost:8080
 * - Database setup created (e.g., "demo-setup")
 * - Queue created (e.g., "demo-queue")
 * - Messages with different types and headers being sent
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-06
 * @version 1.0
 */
public class SSEFilteringExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEFilteringExample.class);
    
    // Configuration
    private static final String REST_API_HOST = "localhost";
    private static final int REST_API_PORT = 8080;
    private static final String SETUP_ID = "demo-setup";
    private static final String QUEUE_NAME = "demo-queue";
    
    public static void main(String[] args) {
        logger.info("=== SSE Filtering Example ===");
        logger.info("Demonstrating SSE filtering, batching, and consumer groups");
        
        Vertx vertx = null;
        HttpClient httpClient = null;
        
        try {
            // Initialize Vert.x and HTTP client
            vertx = Vertx.vertx();
            httpClient = vertx.createHttpClient();
            
            // Demonstrate different filtering patterns
            demonstrateMessageTypeFilter(vertx, httpClient);
            Thread.sleep(2000); // Brief pause between demos
            
            demonstrateHeaderFilter(vertx, httpClient);
            Thread.sleep(2000);
            
            demonstrateBatching(vertx, httpClient);
            Thread.sleep(2000);
            
            demonstrateConsumerGroups(vertx, httpClient);
            
            logger.info("✅ SSE Filtering Example completed successfully");
            
        } catch (Exception e) {
            logger.error("❌ Error in SSE Filtering Example", e);
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
     * Demonstrates filtering by message type.
     * Only receives messages of type "OrderCreated".
     */
    private static void demonstrateMessageTypeFilter(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Message Type Filtering ---");
        
        AtomicInteger messagesReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        // Build SSE URL with messageType filter
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + "/stream?messageType=OrderCreated";
        
        logger.info("📡 Connecting with filter: messageType=OrderCreated");
        logger.info("   URL: {}", sseUrl);
        
        connectAndReceive(httpClient, sseUrl, messagesReceived, latch, 5);
        
        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        
        logger.info("📊 Received {} OrderCreated messages", messagesReceived.get());
    }
    
    /**
     * Demonstrates filtering by headers.
     * Only receives messages with specific header values.
     */
    private static void demonstrateHeaderFilter(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Header Filtering ---");
        
        AtomicInteger messagesReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        // Build SSE URL with header filters
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + 
                       "/stream?headers.region=US-WEST&headers.priority=HIGH";
        
        logger.info("📡 Connecting with filters:");
        logger.info("   region=US-WEST");
        logger.info("   priority=HIGH");
        logger.info("   URL: {}", sseUrl);
        
        connectAndReceive(httpClient, sseUrl, messagesReceived, latch, 5);
        
        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        
        logger.info("📊 Received {} messages matching header filters", messagesReceived.get());
    }
    
    /**
     * Demonstrates batching for efficient message delivery.
     * Receives messages in batches of 5 with 2-second timeout.
     */
    private static void demonstrateBatching(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Batching ---");
        
        AtomicInteger batchesReceived = new AtomicInteger(0);
        AtomicInteger totalMessages = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        // Build SSE URL with batching configuration
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + 
                       "/stream?batchSize=5&maxWaitTime=2000";
        
        logger.info("📡 Connecting with batching:");
        logger.info("   batchSize=5");
        logger.info("   maxWaitTime=2000ms");
        logger.info("   URL: {}", sseUrl);
        
        httpClient.request(HttpMethod.GET, REST_API_PORT, REST_API_HOST, sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                logger.info("✅ SSE connection established");
                
                response.handler(buffer -> {
                    String data = buffer.toString();
                    
                    // Look for batch events
                    if (data.contains("event: batch")) {
                        String[] lines = data.split("\n");
                        for (String line : lines) {
                            if (line.startsWith("data: ")) {
                                try {
                                    JsonObject batchEvent = new JsonObject(line.substring(6));
                                    int messageCount = batchEvent.getInteger("messageCount", 0);
                                    int batchNum = batchesReceived.incrementAndGet();
                                    totalMessages.addAndGet(messageCount);
                                    
                                    logger.info("📦 Batch #{}: {} messages", batchNum, messageCount);
                                    
                                    if (batchNum >= 3) {
                                        response.request().connection().close();
                                        latch.countDown();
                                    }
                                } catch (Exception e) {
                                    logger.error("Error parsing batch event", e);
                                }
                            }
                        }
                    }
                });
                
                response.endHandler(v -> latch.countDown());
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        logger.error("SSE error: {}", err.getMessage());
                    }
                    latch.countDown();
                });
            })
            .onFailure(err -> {
                logger.error("Failed to connect: {}", err.getMessage());
                latch.countDown();
            });
        
        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        
        logger.info("📊 Received {} batches containing {} total messages", 
                   batchesReceived.get(), totalMessages.get());
    }
    
    /**
     * Demonstrates consumer groups for load balancing.
     * Multiple consumers in the same group share message processing.
     */
    private static void demonstrateConsumerGroups(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Consumer Groups ---");
        
        String consumerGroup = "demo-processors";
        
        logger.info("📡 Connecting with consumer group: {}", consumerGroup);
        logger.info("   Multiple consumers in the same group will share messages");
        
        AtomicInteger messagesReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        // Build SSE URL with consumer group
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + 
                       "/stream?consumerGroup=" + consumerGroup;
        
        logger.info("   URL: {}", sseUrl);
        
        connectAndReceive(httpClient, sseUrl, messagesReceived, latch, 5);
        
        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        
        logger.info("📊 Consumer group '{}' received {} messages", consumerGroup, messagesReceived.get());
        logger.info("   Note: Other consumers in the same group would receive different messages");
    }
    
    /**
     * Helper method to connect to SSE and receive messages.
     */
    private static void connectAndReceive(HttpClient httpClient, String sseUrl, 
                                         AtomicInteger messagesReceived, CountDownLatch latch,
                                         int targetCount) {
        httpClient.request(HttpMethod.GET, REST_API_PORT, REST_API_HOST, sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                logger.info("✅ SSE connection established");
                
                response.handler(buffer -> {
                    String data = buffer.toString();
                    
                    // Count data events
                    if (data.contains("\"type\":\"data\"")) {
                        int count = messagesReceived.incrementAndGet();
                        logger.info("📨 Message #{} received", count);
                        
                        if (count >= targetCount) {
                            response.request().connection().close();
                            latch.countDown();
                        }
                    }
                });
                
                response.endHandler(v -> latch.countDown());
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        logger.error("SSE error: {}", err.getMessage());
                    }
                    latch.countDown();
                });
            })
            .onFailure(err -> {
                logger.error("Failed to connect: {}", err.getMessage());
                latch.countDown();
            });
    }
}

