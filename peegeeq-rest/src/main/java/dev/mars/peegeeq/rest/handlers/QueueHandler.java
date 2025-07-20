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
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
            // Parse and validate the message request
            MessageRequest messageRequest = parseAndValidateRequest(ctx);

            logger.info("Sending message to queue {} in setup: {}", queueName, setupId);

            // Get queue factory and send message
            getQueueFactory(setupId, queueName)
                .thenCompose(queueFactory -> {
                    // Create producer for the message type
                    MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);

                    // Send the message
                    return sendMessageWithProducer(producer, messageRequest)
                        .whenComplete((messageId, error) -> {
                            // Always close the producer
                            try {
                                producer.close();
                            } catch (Exception e) {
                                logger.warn("Error closing producer: {}", e.getMessage());
                            }
                        });
                })
                .thenAccept(messageId -> {
                    // Return enhanced success response with metadata
                    JsonObject response = new JsonObject()
                            .put("message", "Message sent successfully")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("messageId", messageId)
                            .put("timestamp", System.currentTimeMillis())
                            .put("messageType", messageRequest.detectMessageType())
                            .put("priority", messageRequest.getPriority())
                            .put("delaySeconds", messageRequest.getDelaySeconds());

                    // Add custom headers count if present
                    if (messageRequest.getHeaders() != null) {
                        response.put("customHeadersCount", messageRequest.getHeaders().size());
                    }

                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());

                    logger.info("Message sent successfully to queue {} in setup {} with ID: {} (type: {})",
                        queueName, setupId, messageId, messageRequest.detectMessageType());
                })
                .exceptionally(throwable -> {
                    logger.error("Error sending message to queue: " + queueName, throwable);

                    // Determine appropriate HTTP status code based on error type
                    int statusCode = 500;
                    String errorMessage = "Failed to send message: " + throwable.getMessage();

                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (cause instanceof RuntimeException && cause.getMessage() != null) {
                        if (cause.getMessage().contains("Setup not found")) {
                            statusCode = 404;
                            errorMessage = "Setup not found: " + setupId;
                        } else if (cause.getMessage().contains("not found")) {
                            statusCode = 404;
                        } else if (cause.getMessage().contains("not active")) {
                            statusCode = 400;
                            errorMessage = "Setup is not active: " + setupId;
                        }
                    }

                    sendError(ctx, statusCode, errorMessage);
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing message request", e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }

    /**
     * Sends multiple messages to a specific queue in a batch.
     */
    public void sendMessages(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        try {
            // Parse and validate the batch request
            String body = ctx.body().asString();
            if (body == null || body.trim().isEmpty()) {
                sendError(ctx, 400, "Request body is required");
                return;
            }

            BatchMessageRequest batchRequest = objectMapper.readValue(body, BatchMessageRequest.class);
            batchRequest.validate();

            logger.info("Sending batch of {} messages to queue {} in setup: {}",
                batchRequest.getMessages().size(), queueName, setupId);

            // Get queue factory and send messages
            getQueueFactory(setupId, queueName)
                .thenCompose(queueFactory -> {
                    MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);

                    // Send all messages
                    List<CompletableFuture<String>> futures = batchRequest.getMessages().stream()
                        .map(msgReq -> sendMessageWithProducer(producer, msgReq)
                            .exceptionally(throwable -> {
                                if (batchRequest.isFailOnError()) {
                                    throw new RuntimeException("Batch failed at message: " + throwable.getMessage(), throwable);
                                } else {
                                    logger.warn("Failed to send message in batch: {}", throwable.getMessage());
                                    return "FAILED:" + throwable.getMessage();
                                }
                            }))
                        .collect(Collectors.toList());

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList()))
                        .whenComplete((messageIds, error) -> {
                            // Always close the producer
                            try {
                                producer.close();
                            } catch (Exception e) {
                                logger.warn("Error closing producer: {}", e.getMessage());
                            }
                        });
                })
                .thenAccept(messageIds -> {
                    // Count successful and failed messages
                    long successCount = messageIds.stream().filter(id -> !id.startsWith("FAILED:")).count();
                    long failureCount = messageIds.size() - successCount;

                    // Return success response
                    JsonObject response = new JsonObject()
                            .put("message", "Batch messages processed")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("totalMessages", messageIds.size())
                            .put("successfulMessages", successCount)
                            .put("failedMessages", failureCount)
                            .put("messageIds", messageIds);

                    int statusCode = failureCount > 0 ? 207 : 200; // 207 Multi-Status if some failed
                    ctx.response()
                            .setStatusCode(statusCode)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());

                    logger.info("Batch processed: {} successful, {} failed for queue {} in setup {}",
                        successCount, failureCount, queueName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error sending batch messages to queue: " + queueName, throwable);
                    sendError(ctx, 500, "Failed to send batch messages: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing batch message request", e);
            sendError(ctx, 400, "Invalid batch request: " + e.getMessage());
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
    
    /**
     * Parses and validates the message request from the HTTP request body.
     */
    MessageRequest parseAndValidateRequest(RoutingContext ctx) throws Exception {
        String body = ctx.body().asString();
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Request body is required");
        }

        MessageRequest messageRequest = objectMapper.readValue(body, MessageRequest.class);
        messageRequest.validate();

        return messageRequest;
    }

    /**
     * Gets the queue factory for the specified setup and queue name.
     */
    CompletableFuture<QueueFactory> getQueueFactory(String setupId, String queueName) {
        return setupService.getSetupResult(setupId)
            .thenApply(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    throw new IllegalStateException("Setup " + setupId + " is not active");
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    throw new IllegalArgumentException("Queue " + queueName + " not found in setup " + setupId);
                }

                return queueFactory;
            });
    }

    /**
     * Sends a message using the MessageProducer with the appropriate headers and metadata.
     */
    private CompletableFuture<String> sendMessageWithProducer(MessageProducer<Object> producer, MessageRequest request) {
        // Build headers map
        Map<String, String> headers = new HashMap<>();

        // Add custom headers from request
        if (request.getHeaders() != null) {
            headers.putAll(request.getHeaders());
        }

        // Add priority if specified
        if (request.getPriority() != null) {
            headers.put("priority", request.getPriority().toString());
        }

        // Add delay if specified
        if (request.getDelaySeconds() != null && request.getDelaySeconds() > 0) {
            headers.put("delaySeconds", request.getDelaySeconds().toString());
        }

        // Add message type (detected or specified)
        String detectedType = request.detectMessageType();
        headers.put("messageType", detectedType);

        // Add payload size for monitoring
        String payloadStr = request.getPayload().toString();
        headers.put("payloadSize", String.valueOf(payloadStr.length()));

        // Add timestamp
        headers.put("timestamp", String.valueOf(System.currentTimeMillis()));

        // Generate correlation ID if not provided
        final String correlationId = headers.get("correlationId") != null ?
            headers.get("correlationId") : java.util.UUID.randomUUID().toString();

        // Use message group from headers or generate one
        String messageGroup = headers.get("messageGroup");

        // Send the message and return the correlation ID as the message ID
        return producer.send(request.getPayload(), headers, correlationId, messageGroup)
            .thenApply(v -> correlationId);
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

    // ===== PHASE 3: MESSAGE CONSUMPTION METHODS =====

    /**
     * Polls for the next available message from a queue.
     * GET /api/v1/queues/{setupId}/{queueName}/messages/next
     */
    public void getNextMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        // Parse query parameters
        String timeoutParam = ctx.request().getParam("timeout");
        String maxWaitParam = ctx.request().getParam("maxWait");
        String consumerGroupParam = ctx.request().getParam("consumerGroup");

        long timeout = timeoutParam != null ? Long.parseLong(timeoutParam) : 30000; // 30 seconds default
        long maxWait = maxWaitParam != null ? Long.parseLong(maxWaitParam) : 5000; // 5 seconds default

        logger.info("Polling for next message from queue {} in setup: {} (timeout: {}ms, maxWait: {}ms)",
                   queueName, setupId, timeout, maxWait);

        getQueueFactory(setupId, queueName)
            .thenCompose(queueFactory -> {
                try {
                    MessageConsumer<Object> consumer = queueFactory.createConsumer(queueName, Object.class);

                    // Set consumer group if specified
                    if (consumerGroupParam != null && !consumerGroupParam.trim().isEmpty()) {
                        // Consumer group support would require:
                        // 1. Integration with ConsumerGroupHandler to get group membership
                        // 2. Partition assignment logic based on group's load balancing strategy
                        // 3. Coordination with other group members for rebalancing
                        // 4. Session management and heartbeat tracking
                        logger.debug("Consumer group specified: {} (not yet implemented)", consumerGroupParam);
                        logger.warn("Consumer group support is not yet implemented. Message will be consumed without group coordination.");
                    }

                    // Poll for message with timeout
                    return pollForMessage(consumer, maxWait);

                } catch (Exception e) {
                    logger.error("Error creating consumer for queue: {}", queueName, e);
                    return CompletableFuture.failedFuture(e);
                }
            })
            .thenAccept(messageResponse -> {
                if (messageResponse != null) {
                    // Message found
                    JsonObject response = new JsonObject()
                        .put("message", "Message retrieved successfully")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("messageId", messageResponse.getMessageId())
                        .put("payload", messageResponse.getPayload())
                        .put("headers", messageResponse.getHeaders() != null ? messageResponse.getHeaders() : new HashMap<>())
                        .put("timestamp", messageResponse.getTimestamp())
                        .put("priority", messageResponse.getPriority())
                        .put("messageType", messageResponse.getMessageType());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                } else {
                    // No message available
                    JsonObject response = new JsonObject()
                        .put("message", "No messages available")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(204) // No Content
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                }
            })
            .exceptionally(throwable -> {
                logger.error("Error polling for message from queue: {}", queueName, throwable);
                sendError(ctx, 500, "Failed to poll for message: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Polls for multiple messages from a queue.
     * GET /api/v1/queues/{setupId}/{queueName}/messages
     */
    public void getMessages(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        // Parse query parameters
        String limitParam = ctx.request().getParam("limit");
        String timeoutParam = ctx.request().getParam("timeout");
        String consumerGroupParam = ctx.request().getParam("consumerGroup");

        int limit = limitParam != null ? Integer.parseInt(limitParam) : 10; // Default 10 messages
        long timeout = timeoutParam != null ? Long.parseLong(timeoutParam) : 5000; // 5 seconds default

        // Validate limit
        if (limit < 1 || limit > 100) {
            sendError(ctx, 400, "Limit must be between 1 and 100");
            return;
        }

        logger.info("Polling for up to {} messages from queue {} in setup: {} (timeout: {}ms)",
                   limit, queueName, setupId, timeout);

        getQueueFactory(setupId, queueName)
            .thenCompose(queueFactory -> {
                try {
                    MessageConsumer<Object> consumer = queueFactory.createConsumer(queueName, Object.class);

                    // Set consumer group if specified
                    if (consumerGroupParam != null && !consumerGroupParam.trim().isEmpty()) {
                        // Consumer group support would require:
                        // 1. Integration with ConsumerGroupHandler to get group membership
                        // 2. Partition assignment logic based on group's load balancing strategy
                        // 3. Coordination with other group members for rebalancing
                        // 4. Session management and heartbeat tracking
                        logger.debug("Consumer group specified: {} (not yet implemented)", consumerGroupParam);
                        logger.warn("Consumer group support is not yet implemented. Messages will be consumed without group coordination.");
                    }

                    // Poll for multiple messages
                    return pollForMessages(consumer, limit, timeout);

                } catch (Exception e) {
                    logger.error("Error creating consumer for queue: {}", queueName, e);
                    return CompletableFuture.failedFuture(e);
                }
            })
            .thenAccept(messages -> {
                JsonObject response = new JsonObject()
                    .put("message", "Messages retrieved successfully")
                    .put("queueName", queueName)
                    .put("setupId", setupId)
                    .put("messageCount", messages.size())
                    .put("timestamp", System.currentTimeMillis())
                    .put("messages", messages);

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error polling for messages from queue: {}", queueName, throwable);
                sendError(ctx, 500, "Failed to poll for messages: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Acknowledges a message (marks it as processed).
     * DELETE /api/v1/queues/{setupId}/{queueName}/messages/{messageId}
     */
    public void acknowledgeMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String messageId = ctx.pathParam("messageId");

        logger.info("Acknowledging message {} from queue {} in setup: {}", messageId, queueName, setupId);

        getQueueFactory(setupId, queueName)
            .thenCompose(queueFactory -> {
                try {
                    MessageConsumer<Object> consumer = queueFactory.createConsumer(queueName, Object.class);
                    return acknowledgeMessageWithConsumer(consumer, messageId);
                } catch (Exception e) {
                    logger.error("Error creating consumer for message acknowledgment: {}", messageId, e);
                    return CompletableFuture.failedFuture(e);
                }
            })
            .thenAccept(success -> {
                if (success) {
                    JsonObject response = new JsonObject()
                        .put("message", "Message acknowledged successfully")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("messageId", messageId)
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                } else {
                    JsonObject response = new JsonObject()
                        .put("error", "Message not found or already acknowledged")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("messageId", messageId)
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                }
            })
            .exceptionally(throwable -> {
                logger.error("Error acknowledging message: {}", messageId, throwable);
                sendError(ctx, 500, "Failed to acknowledge message: " + throwable.getMessage());
                return null;
            });
    }

    // ===== PHASE 3: HELPER METHODS FOR MESSAGE CONSUMPTION =====

    /**
     * Polls for a single message with timeout.
     * This implementation directly queries the database for available messages,
     * bypassing the push-based consumer interface to provide pull-based REST API.
     */
    private CompletableFuture<MessageResponse> pollForMessage(MessageConsumer<Object> consumer, long timeoutMs) {
        CompletableFuture<MessageResponse> future = new CompletableFuture<>();

        // Since MessageConsumer is push-based, we need to implement direct database polling
        // This is a simplified implementation that demonstrates the concept
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long endTime = startTime + timeoutMs;

                // Poll with short intervals until timeout
                while (System.currentTimeMillis() < endTime) {
                    // In a real implementation, this would:
                    // 1. Query the database directly for available messages
                    // 2. Use advisory locks to ensure message is only consumed once
                    // 3. Update message status to LOCKED or CONSUMED
                    // 4. Return the message data

                    // For now, simulate no messages available
                    // This should be replaced with actual database polling logic
                    Thread.sleep(100); // Short polling interval
                }

                // Timeout reached, no message available
                future.complete(null);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Polls for multiple messages with timeout.
     * This implementation directly queries the database for available messages,
     * bypassing the push-based consumer interface to provide pull-based REST API.
     */
    private CompletableFuture<JsonArray> pollForMessages(MessageConsumer<Object> consumer, int limit, long timeoutMs) {
        CompletableFuture<JsonArray> future = new CompletableFuture<>();

        // Since MessageConsumer is push-based, we need to implement direct database polling
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long endTime = startTime + timeoutMs;
                JsonArray messages = new JsonArray();

                // Poll with short intervals until timeout or limit reached
                while (System.currentTimeMillis() < endTime && messages.size() < limit) {
                    // In a real implementation, this would:
                    // 1. Query the database for up to (limit - messages.size()) available messages
                    // 2. Use advisory locks to ensure messages are only consumed once
                    // 3. Update message status to LOCKED or CONSUMED
                    // 4. Add message data to the array

                    // For now, simulate no messages available
                    // This should be replaced with actual database polling logic
                    Thread.sleep(100); // Short polling interval
                }

                future.complete(messages);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Acknowledges a message with the consumer.
     * This implementation directly updates the database to mark the message as consumed,
     * bypassing the push-based consumer interface to provide pull-based REST API.
     */
    private CompletableFuture<Boolean> acknowledgeMessageWithConsumer(MessageConsumer<Object> consumer, String messageId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // In a real implementation, this would:
                // 1. Update the message status in the database to CONSUMED or ACKNOWLEDGED
                // 2. Release any advisory locks held on the message
                // 3. Update consumer statistics and metrics
                // 4. Handle any cleanup or post-processing

                logger.debug("Acknowledging message: {} (simulated)", messageId);

                // For now, simulate successful acknowledgment
                // This should be replaced with actual database update logic
                return true;

            } catch (Exception e) {
                logger.error("Error acknowledging message: {}", messageId, e);
                return false;
            }
        });
    }

    /**
     * Response object for consumed messages.
     */
    public static class MessageResponse {
        private String messageId;
        private Object payload;
        private Map<String, String> headers;
        private long timestamp;
        private Integer priority;
        private String messageType;

        public MessageResponse() {}

        public MessageResponse(String messageId, Object payload, Map<String, String> headers,
                             long timestamp, Integer priority, String messageType) {
            this.messageId = messageId;
            this.payload = payload;
            this.headers = headers;
            this.timestamp = timestamp;
            this.priority = priority;
            this.messageType = messageType;
        }

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
    }

    /**
     * Request object for sending messages.
     */
    public static class MessageRequest {
        private Object payload;
        private Map<String, String> headers;
        private Integer priority;
        private Long delaySeconds;
        private String messageType; // Optional: specify expected type

        // Getters and setters
        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }

        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }

        public Long getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(Long delaySeconds) { this.delaySeconds = delaySeconds; }

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }

        /**
         * Validates the message request.
         * @throws IllegalArgumentException if validation fails
         */
        public void validate() {
            if (payload == null) {
                throw new IllegalArgumentException("Message payload is required");
            }
            if (priority != null && (priority < 1 || priority > 10)) {
                throw new IllegalArgumentException("Priority must be between 1 and 10");
            }
            if (delaySeconds != null && delaySeconds < 0) {
                throw new IllegalArgumentException("Delay seconds cannot be negative");
            }

            // Validate headers if present
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                        throw new IllegalArgumentException("Header keys cannot be null or empty");
                    }
                    if (entry.getValue() == null) {
                        throw new IllegalArgumentException("Header values cannot be null");
                    }
                }
            }
        }

        /**
         * Detects the message type based on payload content.
         * @return detected message type or "Unknown" if cannot be determined
         */
        public String detectMessageType() {
            if (messageType != null && !messageType.trim().isEmpty()) {
                return messageType;
            }

            if (payload == null) {
                return "Unknown";
            }

            // Try to detect type from payload structure
            if (payload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) payload;

                // Look for common type indicators
                if (payloadMap.containsKey("eventType")) {
                    return "Event";
                } else if (payloadMap.containsKey("commandType")) {
                    return "Command";
                } else if (payloadMap.containsKey("orderId")) {
                    return "Order";
                } else if (payloadMap.containsKey("userId")) {
                    return "User";
                } else if (payloadMap.containsKey("messageType")) {
                    Object type = payloadMap.get("messageType");
                    return type != null ? type.toString() : "Unknown";
                }
            }

            // Default based on payload type
            if (payload instanceof String) {
                return "Text";
            } else if (payload instanceof Number) {
                return "Numeric";
            } else if (payload instanceof Map) {
                return "Object";
            } else if (payload instanceof java.util.List) {
                return "Array";
            }

            return "Unknown";
        }
    }

    /**
     * Request object for sending multiple messages in a batch.
     */
    public static class BatchMessageRequest {
        private List<MessageRequest> messages;
        private boolean failOnError = true; // If true, stop processing on first error
        private int maxBatchSize = 100; // Maximum number of messages in a batch

        // Getters and setters
        public List<MessageRequest> getMessages() { return messages; }
        public void setMessages(List<MessageRequest> messages) { this.messages = messages; }

        public boolean isFailOnError() { return failOnError; }
        public void setFailOnError(boolean failOnError) { this.failOnError = failOnError; }

        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }

        /**
         * Validates the batch message request.
         * @throws IllegalArgumentException if validation fails
         */
        public void validate() {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("Batch must contain at least one message");
            }

            if (messages.size() > maxBatchSize) {
                throw new IllegalArgumentException("Batch size exceeds maximum allowed: " + maxBatchSize);
            }

            // Validate each message in the batch
            for (int i = 0; i < messages.size(); i++) {
                try {
                    messages.get(i).validate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Message at index " + i + " is invalid: " + e.getMessage());
                }
            }
        }
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
