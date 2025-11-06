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
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example demonstrating SSE error handling and reconnection with Last-Event-ID.
 * 
 * This example shows how to:
 * 1. Classify errors (transient vs permanent)
 * 2. Implement exponential backoff reconnection with jitter
 * 3. Use Last-Event-ID for automatic reconnection (SSE standard)
 * 4. Track last processed message ID
 * 5. Resume from last known position
 * 6. Handle connection failures gracefully
 * 
 * Prerequisites:
 * - PeeGeeQ REST server running on localhost:8080
 * - Database setup created (e.g., "demo-setup")
 * - Queue created (e.g., "demo-queue")
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-06
 * @version 1.0
 */
public class SSEErrorHandlingExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEErrorHandlingExample.class);
    
    // Configuration
    private static final String REST_API_HOST = "localhost";
    private static final int REST_API_PORT = 8080;
    private static final String SETUP_ID = "demo-setup";
    private static final String QUEUE_NAME = "demo-queue";
    
    // Reconnection configuration
    private static final int BASE_DELAY_MS = 1000;      // 1 second
    private static final int MAX_DELAY_MS = 60000;      // 60 seconds
    private static final int MAX_ATTEMPTS = 10;         // Give up after 10 attempts
    private static final int JITTER_MS = 500;           // Random jitter up to 500ms
    
    // Error types
    private enum ErrorType {
        TRANSIENT,   // Network timeout, connection refused, temporary unavailability
        PERMANENT    // Authentication failure, invalid setup/queue, authorization error
    }
    
    public static void main(String[] args) {
        logger.info("=== SSE Error Handling Example ===");
        logger.info("Demonstrating error handling and reconnection with Last-Event-ID");
        
        Vertx vertx = null;
        HttpClient httpClient = null;
        
        try {
            // Initialize Vert.x and HTTP client
            vertx = Vertx.vertx();
            httpClient = vertx.createHttpClient();
            
            // Demonstrate error handling and reconnection
            demonstrateErrorHandling(vertx, httpClient);
            
            logger.info("✅ SSE Error Handling Example completed successfully");
            
        } catch (Exception e) {
            logger.error("❌ Error in SSE Error Handling Example", e);
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
     * Demonstrates error handling and automatic reconnection.
     */
    private static void demonstrateErrorHandling(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Error Handling and Reconnection Demo ---");
        
        AtomicInteger messagesReceived = new AtomicInteger(0);
        AtomicInteger reconnectionAttempts = new AtomicInteger(0);
        AtomicReference<String> lastEventId = new AtomicReference<>(null);
        CountDownLatch completionLatch = new CountDownLatch(1);
        
        // Start initial connection
        connectWithReconnection(vertx, httpClient, messagesReceived, reconnectionAttempts, 
                               lastEventId, completionLatch, 0);
        
        // Wait for completion (with generous timeout for reconnection attempts)
        boolean completed = completionLatch.await(120, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("⚠️ Error handling demo timed out");
        }
        
        logger.info("\n--- Final Statistics ---");
        logger.info("📊 Total Messages Received: {}", messagesReceived.get());
        logger.info("🔄 Total Reconnection Attempts: {}", reconnectionAttempts.get());
        logger.info("🆔 Last Event ID: {}", lastEventId.get());
    }
    
    /**
     * Connects to SSE with automatic reconnection support.
     */
    private static void connectWithReconnection(Vertx vertx, HttpClient httpClient,
                                               AtomicInteger messagesReceived,
                                               AtomicInteger reconnectionAttempts,
                                               AtomicReference<String> lastEventId,
                                               CountDownLatch completionLatch,
                                               int attemptNumber) {
        
        // Check if we've exceeded max attempts
        if (attemptNumber > MAX_ATTEMPTS) {
            logger.error("❌ Max reconnection attempts ({}) reached, giving up", MAX_ATTEMPTS);
            completionLatch.countDown();
            return;
        }
        
        // Build SSE endpoint URL
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + "/stream";
        
        if (attemptNumber == 0) {
            logger.info("📡 Initial connection to SSE endpoint: {}", sseUrl);
        } else {
            logger.info("🔄 Reconnection attempt #{}/{}", attemptNumber, MAX_ATTEMPTS);
            reconnectionAttempts.incrementAndGet();
        }
        
        // Connect to SSE endpoint
        httpClient.request(HttpMethod.GET, REST_API_PORT, REST_API_HOST, sseUrl)
            .compose(req -> {
                // Add Last-Event-ID header for reconnection (SSE standard)
                String lastId = lastEventId.get();
                if (lastId != null) {
                    req.putHeader("Last-Event-ID", lastId);
                    logger.info("🆔 Reconnecting with Last-Event-ID: {}", lastId);
                }
                return req.send();
            })
            .onSuccess(response -> {
                logger.info("✅ SSE connection established (attempt #{})", attemptNumber);
                logger.info("   Status: {}", response.statusCode());
                
                // Handle SSE data stream
                response.handler(buffer -> {
                    String data = buffer.toString();
                    
                    // Parse SSE events and track Last-Event-ID
                    parseSSEEventsWithTracking(data, messagesReceived, lastEventId);
                    
                    // Simulate disconnection after 5 messages for demo purposes
                    if (messagesReceived.get() == 5 && attemptNumber == 0) {
                        logger.info("🔌 Simulating disconnection for reconnection demo...");
                        response.request().connection().close();
                    }
                    
                    // Complete after receiving 10 messages total
                    if (messagesReceived.get() >= 10) {
                        logger.info("✅ Received target number of messages, closing connection");
                        response.request().connection().close();
                        completionLatch.countDown();
                    }
                });
                
                // Handle connection close
                response.endHandler(v -> {
                    logger.info("📡 SSE connection closed");
                    
                    // Don't reconnect if we've completed
                    if (messagesReceived.get() < 10) {
                        // Reconnect with exponential backoff
                        reconnectWithBackoff(vertx, httpClient, messagesReceived, reconnectionAttempts,
                                           lastEventId, completionLatch, attemptNumber + 1);
                    }
                });
                
                // Handle errors
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        ErrorType errorType = classifyError(err);
                        logger.error("❌ SSE connection error ({}): {}", errorType, err.getMessage());
                        
                        if (errorType == ErrorType.PERMANENT) {
                            logger.error("🛑 Permanent error detected, not retrying");
                            completionLatch.countDown();
                        } else {
                            // Reconnect for transient errors
                            reconnectWithBackoff(vertx, httpClient, messagesReceived, reconnectionAttempts,
                                               lastEventId, completionLatch, attemptNumber + 1);
                        }
                    }
                });
            })
            .onFailure(err -> {
                ErrorType errorType = classifyError(err);
                logger.error("❌ Failed to connect to SSE endpoint ({}): {}", errorType, err.getMessage());
                
                if (errorType == ErrorType.PERMANENT) {
                    logger.error("🛑 Permanent error detected, not retrying");
                    completionLatch.countDown();
                } else {
                    // Reconnect with exponential backoff
                    reconnectWithBackoff(vertx, httpClient, messagesReceived, reconnectionAttempts,
                                       lastEventId, completionLatch, attemptNumber + 1);
                }
            });
    }
    
    /**
     * Reconnects with exponential backoff and jitter.
     */
    private static void reconnectWithBackoff(Vertx vertx, HttpClient httpClient,
                                            AtomicInteger messagesReceived,
                                            AtomicInteger reconnectionAttempts,
                                            AtomicReference<String> lastEventId,
                                            CountDownLatch completionLatch,
                                            int attemptNumber) {
        
        // Calculate delay with exponential backoff and jitter
        int delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1 << (attemptNumber - 1)));
        delay += ThreadLocalRandom.current().nextInt(JITTER_MS);
        
        logger.info("⏱️  Reconnecting in {} ms (attempt {}/{})", delay, attemptNumber, MAX_ATTEMPTS);
        
        // Schedule reconnection
        vertx.setTimer(delay, id -> {
            connectWithReconnection(vertx, httpClient, messagesReceived, reconnectionAttempts,
                                  lastEventId, completionLatch, attemptNumber);
        });
    }
    
    /**
     * Parses SSE events and tracks the Last-Event-ID.
     */
    private static void parseSSEEventsWithTracking(String data, AtomicInteger messagesReceived,
                                                   AtomicReference<String> lastEventId) {
        String[] lines = data.split("\n");
        
        String eventId = null;
        
        for (String line : lines) {
            if (line.startsWith("id: ")) {
                eventId = line.substring(4).trim();
            } else if (line.startsWith("data: ")) {
                try {
                    JsonObject event = new JsonObject(line.substring(6));
                    String type = event.getString("type");
                    
                    if ("data".equals(type)) {
                        int count = messagesReceived.incrementAndGet();
                        String messageId = event.getString("messageId");
                        
                        // Update last event ID
                        if (eventId != null) {
                            lastEventId.set(eventId);
                        } else if (messageId != null) {
                            lastEventId.set(messageId);
                        }
                        
                        logger.info("📨 Message #{}: {} (Last-Event-ID: {})", 
                                   count, messageId, lastEventId.get());
                    }
                } catch (Exception e) {
                    logger.debug("Error parsing SSE event: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * Classifies errors as transient or permanent.
     */
    private static ErrorType classifyError(Throwable error) {
        String message = error.getMessage().toLowerCase();
        
        // Permanent errors
        if (message.contains("authentication") || 
            message.contains("authorization") ||
            message.contains("forbidden") ||
            message.contains("not found") ||
            message.contains("invalid")) {
            return ErrorType.PERMANENT;
        }
        
        // Transient errors (default)
        return ErrorType.TRANSIENT;
    }
}

