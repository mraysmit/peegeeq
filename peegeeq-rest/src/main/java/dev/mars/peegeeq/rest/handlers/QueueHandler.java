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

package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handler for queue operation REST endpoints.
 * 
 * Handles HTTP requests for queue operations including sending messages
 * and retrieving queue statistics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
public class QueueHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueHandler.class);
    
    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;
    
    public QueueHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Sends a message to a specific queue.
     */
    public void sendMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        
        try {
            String body = ctx.getBodyAsString();
            MessageRequest messageRequest = objectMapper.readValue(body, MessageRequest.class);
            
            logger.info("Sending message to queue {} in setup: {}", queueName, setupId);
            
            // For now, return a placeholder response
            // In a complete implementation, this would:
            // 1. Get the setup from setupService
            // 2. Get the queue factory for the setup
            // 3. Create a producer and send the message
            
            setupService.getSetupStatus(setupId)
                    .thenAccept(status -> {
                        JsonObject response = new JsonObject()
                                .put("message", "Message sent successfully")
                                .put("queueName", queueName)
                                .put("setupId", setupId)
                                .put("messageId", java.util.UUID.randomUUID().toString());
                        
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                        
                        logger.info("Message sent successfully to queue {} in setup {}", queueName, setupId);
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error sending message to queue: " + queueName, throwable);
                        sendError(ctx, 400, "Failed to send message: " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            logger.error("Error parsing send message request", e);
            sendError(ctx, 400, "Invalid request format");
        }
    }
    
    /**
     * Gets queue statistics.
     */
    public void getQueueStats(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        
        logger.info("Getting stats for queue {} in setup: {}", queueName, setupId);
        
        // For now, return placeholder statistics
        // In a complete implementation, this would get actual queue metrics
        
        setupService.getSetupStatus(setupId)
                .thenAccept(status -> {
                    QueueStats stats = new QueueStats(queueName, 0L, 0L, 0L);
                    
                    try {
                        String responseJson = objectMapper.writeValueAsString(stats);
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(responseJson);
                    } catch (Exception e) {
                        logger.error("Error serializing queue stats", e);
                        sendError(ctx, 500, "Internal server error");
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error getting queue stats: " + queueName, throwable);
                    sendError(ctx, 404, "Queue not found");
                    return null;
                });
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }
    
    /**
     * Request object for sending messages.
     */
    public static class MessageRequest {
        private Object payload;
        private Map<String, String> headers;
        private Integer priority;
        private Long delaySeconds;
        
        // Getters and setters
        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }
        
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
        
        public Long getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(Long delaySeconds) { this.delaySeconds = delaySeconds; }
    }
    
    /**
     * Queue statistics response object.
     */
    public static class QueueStats {
        private final String queueName;
        private final long totalMessages;
        private final long pendingMessages;
        private final long processedMessages;
        
        public QueueStats(String queueName, long totalMessages, long pendingMessages, long processedMessages) {
            this.queueName = queueName;
            this.totalMessages = totalMessages;
            this.pendingMessages = pendingMessages;
            this.processedMessages = processedMessages;
        }
        
        public String getQueueName() { return queueName; }
        public long getTotalMessages() { return totalMessages; }
        public long getPendingMessages() { return pendingMessages; }
        public long getProcessedMessages() { return processedMessages; }
    }
}
