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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example demonstrating SSE connection lifecycle management and metrics.
 * 
 * This example shows how to:
 * 1. Track connection state (CONNECTING, ACTIVE, IDLE, CLOSED)
 * 2. Collect metrics (messages received, latency, throughput)
 * 3. Monitor connection activity
 * 4. Handle connection timeouts
 * 5. Implement proper resource cleanup
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
public class SSEConnectionManagementExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEConnectionManagementExample.class);
    
    // Configuration
    private static final String REST_API_HOST = "localhost";
    private static final int REST_API_PORT = 8080;
    private static final String SETUP_ID = "demo-setup";
    private static final String QUEUE_NAME = "demo-queue";
    private static final long IDLE_TIMEOUT_MS = 10000; // 10 seconds
    private static final long CONNECTION_TIMEOUT_MS = 30000; // 30 seconds
    
    // Connection states
    private enum ConnectionState {
        CONNECTING,
        ACTIVE,
        IDLE,
        CLOSED,
        ERROR
    }
    
    // Metrics
    private static class ConnectionMetrics {
        final AtomicInteger messagesReceived = new AtomicInteger(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final AtomicLong connectionStartTime = new AtomicLong(0);
        final AtomicLong lastActivityTime = new AtomicLong(0);
        final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CONNECTING);
        
        void recordMessage(long latencyMs) {
            messagesReceived.incrementAndGet();
            totalLatencyMs.addAndGet(latencyMs);
            lastActivityTime.set(System.currentTimeMillis());
        }
        
        void printStatistics() {
            int msgCount = messagesReceived.get();
            long totalLatency = totalLatencyMs.get();
            long connectionTime = System.currentTimeMillis() - connectionStartTime.get();
            
            logger.info("📊 Connection Statistics:");
            logger.info("   State: {}", state.get());
            logger.info("   Messages Received: {}", msgCount);
            logger.info("   Average Latency: {} ms", msgCount > 0 ? totalLatency / msgCount : 0);
            logger.info("   Throughput: {} msg/sec", 
                       connectionTime > 0 ? (msgCount * 1000.0 / connectionTime) : 0);
            logger.info("   Connection Uptime: {} seconds", connectionTime / 1000);
            logger.info("   Last Activity: {} ms ago", 
                       System.currentTimeMillis() - lastActivityTime.get());
        }
    }
    
    public static void main(String[] args) {
        logger.info("=== SSE Connection Management Example ===");
        logger.info("Demonstrating connection lifecycle and metrics tracking");
        
        Vertx vertx = null;
        HttpClient httpClient = null;
        
        try {
            // Initialize Vert.x and HTTP client
            vertx = Vertx.vertx();
            httpClient = vertx.createHttpClient();
            
            // Demonstrate connection management
            demonstrateConnectionManagement(vertx, httpClient);
            
            logger.info("✅ SSE Connection Management Example completed successfully");
            
        } catch (Exception e) {
            logger.error("❌ Error in SSE Connection Management Example", e);
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
     * Demonstrates comprehensive connection management with metrics.
     */
    private static void demonstrateConnectionManagement(Vertx vertx, HttpClient httpClient) throws Exception {
        logger.info("\n--- Connection Management Demo ---");
        
        ConnectionMetrics metrics = new ConnectionMetrics();
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<HttpClientResponse> responseRef = new AtomicReference<>();
        
        // Build SSE endpoint URL
        String sseUrl = "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME + "/stream";
        
        logger.info("📡 Connecting to SSE endpoint: {}", sseUrl);
        metrics.state.set(ConnectionState.CONNECTING);
        
        // Connect to SSE endpoint
        httpClient.request(HttpMethod.GET, REST_API_PORT, REST_API_HOST, sseUrl)
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                responseRef.set(response);
                metrics.connectionStartTime.set(System.currentTimeMillis());
                metrics.lastActivityTime.set(System.currentTimeMillis());
                metrics.state.set(ConnectionState.ACTIVE);
                
                logger.info("✅ SSE connection established");
                logger.info("   Status: {}", response.statusCode());
                logger.info("   State: {}", metrics.state.get());
                
                // Handle SSE data stream
                response.handler(buffer -> {
                    String data = buffer.toString();
                    long receiveTime = System.currentTimeMillis();
                    
                    // Parse and track messages
                    if (data.contains("\"type\":\"data\"")) {
                        try {
                            // Extract timestamp from message for latency calculation
                            String[] lines = data.split("\n");
                            for (String line : lines) {
                                if (line.startsWith("data: ")) {
                                    JsonObject event = new JsonObject(line.substring(6));
                                    long messageTimestamp = event.getLong("timestamp", receiveTime);
                                    long latency = receiveTime - messageTimestamp;
                                    
                                    metrics.recordMessage(latency);
                                    
                                    int count = metrics.messagesReceived.get();
                                    logger.info("📨 Message #{} received (latency: {} ms)", count, latency);
                                    
                                    // Update state to ACTIVE
                                    if (metrics.state.get() == ConnectionState.IDLE) {
                                        metrics.state.set(ConnectionState.ACTIVE);
                                        logger.info("🔄 Connection state: IDLE → ACTIVE");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Error processing message", e);
                        }
                    }
                });
                
                // Handle connection close
                response.endHandler(v -> {
                    metrics.state.set(ConnectionState.CLOSED);
                    logger.info("📡 SSE connection closed");
                    logger.info("   State: {}", metrics.state.get());
                    metrics.printStatistics();
                    completionLatch.countDown();
                });
                
                // Handle errors
                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        metrics.state.set(ConnectionState.ERROR);
                        logger.error("❌ SSE connection error: {}", err.getMessage());
                        logger.info("   State: {}", metrics.state.get());
                    }
                    completionLatch.countDown();
                });
                
                // Start activity monitoring
                startActivityMonitoring(vertx, metrics, response);
                
                // Start connection timeout monitoring
                startConnectionTimeout(vertx, metrics, response, completionLatch);
            })
            .onFailure(err -> {
                metrics.state.set(ConnectionState.ERROR);
                logger.error("❌ Failed to connect to SSE endpoint: {}", err.getMessage());
                logger.info("   State: {}", metrics.state.get());
                completionLatch.countDown();
            });
        
        // Wait for completion
        boolean completed = completionLatch.await(CONNECTION_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
        if (!completed) {
            logger.warn("⚠️ Connection management demo timed out");
            if (responseRef.get() != null) {
                responseRef.get().request().connection().close();
            }
        }
        
        // Print final statistics
        logger.info("\n--- Final Statistics ---");
        metrics.printStatistics();
    }
    
    /**
     * Monitors connection activity and detects idle state.
     */
    private static void startActivityMonitoring(Vertx vertx, ConnectionMetrics metrics, 
                                               HttpClientResponse response) {
        vertx.setPeriodic(5000, timerId -> {
            long idleTime = System.currentTimeMillis() - metrics.lastActivityTime.get();
            
            if (idleTime > IDLE_TIMEOUT_MS && metrics.state.get() == ConnectionState.ACTIVE) {
                metrics.state.set(ConnectionState.IDLE);
                logger.info("⏸️  Connection idle for {} seconds", idleTime / 1000);
                logger.info("   State: ACTIVE → IDLE");
            }
            
            // Cancel timer if connection is closed
            if (metrics.state.get() == ConnectionState.CLOSED || 
                metrics.state.get() == ConnectionState.ERROR) {
                vertx.cancelTimer(timerId);
            }
        });
    }
    
    /**
     * Implements connection timeout.
     */
    private static void startConnectionTimeout(Vertx vertx, ConnectionMetrics metrics, 
                                              HttpClientResponse response, CountDownLatch latch) {
        vertx.setTimer(CONNECTION_TIMEOUT_MS, timerId -> {
            if (metrics.state.get() != ConnectionState.CLOSED && 
                metrics.state.get() != ConnectionState.ERROR) {
                logger.info("⏱️  Connection timeout reached ({} seconds)", CONNECTION_TIMEOUT_MS / 1000);
                logger.info("   Closing connection gracefully");
                response.request().connection().close();
                latch.countDown();
            }
        });
    }
}

