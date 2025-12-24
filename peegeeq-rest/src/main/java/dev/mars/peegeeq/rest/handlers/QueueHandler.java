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
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

        // Set MDC for distributed tracing
        TraceContextUtil.setMDC(TraceContextUtil.MDC_SETUP_ID, setupId);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_QUEUE_NAME, queueName);

        // Extract and set trace context from W3C traceparent header
        String traceparent = ctx.request().getHeader("traceparent");
        if (traceparent != null) {
            TraceContextUtil.setMDCFromTraceparent(traceparent);
        }

        try {
            // Parse and validate the message request
            MessageRequest messageRequest = parseAndValidateRequest(ctx);

            // Set correlation ID in MDC (will be set in sendMessageWithProducer)
            logger.info("Sending message to queue {} in setup: {}", queueName, setupId);

            // Get queue factory and send message
            getQueueFactory(setupId, queueName)
                .thenCompose(queueFactory -> {
                    // Create producer for the message type
                    MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);

                    // Send the message
                    return sendMessageWithProducer(producer, messageRequest, ctx)
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
                    // Set correlation ID in MDC for logging
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, messageId);
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, messageId);

                    // Return enhanced success response with metadata
                    JsonObject response = new JsonObject()
                            .put("message", "Message sent successfully")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("messageId", messageId)
                            .put("correlationId", messageId) // messageId is the correlationId
                            .put("timestamp", System.currentTimeMillis())
                            .put("messageType", messageRequest.detectMessageType())
                            .put("priority", messageRequest.getPriority())
                            .put("delaySeconds", messageRequest.getDelaySeconds());

                    // Add message group if present
                    if (messageRequest.getMessageGroup() != null) {
                        response.put("messageGroup", messageRequest.getMessageGroup());
                    }

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
                    // Check if this is an expected setup not found error (no stack trace)
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("🚫 EXPECTED: Setup not found for queue operation: {} (setup: {})",
                                   queueName, setupId);
                    } else if (isTestScenario(setupId, throwable)) {
                        logger.info("🧪 EXPECTED TEST ERROR - Error sending message to queue: {} (setup: {}) - {}",
                                   queueName, setupId, throwable.getMessage());
                    } else {
                        logger.error("Error sending message to queue: " + queueName, throwable);
                    }

                    // Determine appropriate HTTP status code based on error type
                    int statusCode = 500;
                    String errorMessage = "Failed to send message: " + throwable.getMessage();

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
                })
                .whenComplete((result, error) -> {
                    // Clear MDC after request completes
                    TraceContextUtil.clearTraceMDC();
                });

        } catch (Exception e) {
            logger.error("Error parsing message request", e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
            // Clear MDC on exception
            TraceContextUtil.clearTraceMDC();
        }
    }

    /**
     * Sends multiple messages to a specific queue in a batch.
     */
    public void sendMessages(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        // Set MDC for distributed tracing
        TraceContextUtil.setMDC(TraceContextUtil.MDC_SETUP_ID, setupId);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_QUEUE_NAME, queueName);

        // Extract and set trace context from W3C traceparent header
        String traceparent = ctx.request().getHeader("traceparent");
        if (traceparent != null) {
            TraceContextUtil.setMDCFromTraceparent(traceparent);
        }

        try {
            // Parse and validate the batch request
            String body = ctx.body().asString();
            if (body == null || body.trim().isEmpty()) {
                sendError(ctx, 400, "Request body is required");
                TraceContextUtil.clearTraceMDC();
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

                    // Send all messages (propagate same trace context to all messages in batch)
                    List<CompletableFuture<String>> futures = batchRequest.getMessages().stream()
                        .map(msgReq -> sendMessageWithProducer(producer, msgReq, ctx)
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
                    // Check if this is an intentional test error
                    if (isTestScenario(setupId, throwable)) {
                        logger.info("🧪 EXPECTED TEST ERROR - Error sending batch messages to queue: {} (setup: {}) - {}",
                                   queueName, setupId, throwable.getMessage());
                    } else {
                        logger.error("Error sending batch messages to queue: " + queueName, throwable);
                    }
                    sendError(ctx, 500, "Failed to send batch messages: " + throwable.getMessage());
                    return null;
                })
                .whenComplete((result, error) -> {
                    // Clear MDC after request completes
                    TraceContextUtil.clearTraceMDC();
                });

        } catch (Exception e) {
            logger.error("Error parsing batch message request", e);
            sendError(ctx, 400, "Invalid batch request: " + e.getMessage());
            // Clear MDC on exception
            TraceContextUtil.clearTraceMDC();
        }
    }
    
    /**
     * Gets queue statistics.
     *
     * This endpoint returns real queue statistics from the database including message counts,
     * processing rates, and timing information via QueueFactory.getStats().
     */
    public void getQueueStats(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Getting stats for queue {} in setup: {}", queueName, setupId);

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }

                    // Get real stats from the queue factory
                    boolean isHealthy = queueFactory.isHealthy();
                    String implementationType = queueFactory.getImplementationType();

                    // Get real statistics from the database via QueueFactory.getStats()
                    dev.mars.peegeeq.api.messaging.QueueStats stats = queueFactory.getStats(queueName);

                    try {
                        // Build response with real statistics
                        JsonObject response = new JsonObject()
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("implementationType", implementationType)
                            .put("healthy", isHealthy)
                            .put("totalMessages", stats.getTotalMessages())
                            .put("pendingMessages", stats.getPendingMessages())
                            .put("processedMessages", stats.getProcessedMessages())
                            .put("inFlightMessages", stats.getInFlightMessages())
                            .put("deadLetteredMessages", stats.getDeadLetteredMessages())
                            .put("messagesPerSecond", stats.getMessagesPerSecond())
                            .put("avgProcessingTimeMs", stats.getAvgProcessingTimeMs())
                            .put("successRatePercent", stats.getSuccessRatePercent())
                            .put("timestamp", System.currentTimeMillis());

                        // Add optional timing fields if available
                        if (stats.getCreatedAt() != null) {
                            response.put("firstMessageAt", stats.getCreatedAt().toString());
                        }
                        if (stats.getLastMessageAt() != null) {
                            response.put("lastMessageAt", stats.getLastMessageAt().toString());
                        }

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved stats for queue {} (type: {}, healthy: {}, total: {}, pending: {})",
                                   queueName, implementationType, isHealthy,
                                   stats.getTotalMessages(), stats.getPendingMessages());
                    } catch (Exception e) {
                        logger.error("Error serializing queue stats", e);
                        sendError(ctx, 500, "Internal server error");
                    }
                })
                .exceptionally(throwable -> {
                    // Check if this is an expected setup not found error (no stack trace)
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("EXPECTED: Setup not found for queue stats: {} (setup: {})",
                                   queueName, setupId);
                    } else if (isTestScenario(setupId, throwable)) {
                        logger.info("EXPECTED TEST ERROR - Error getting queue stats: {} (setup: {}) - {}",
                                   queueName, setupId, throwable.getMessage());
                    } else {
                        logger.error("Error getting queue stats: " + queueName, throwable);
                    }
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
     * Extracts W3C Trace Context headers from the HTTP request and propagates them to the message.
     */
    private CompletableFuture<String> sendMessageWithProducer(MessageProducer<Object> producer, MessageRequest request, RoutingContext ctx) {
        // Build headers map
        Map<String, String> headers = new HashMap<>();

        // Add custom headers from request
        if (request.getHeaders() != null) {
            headers.putAll(request.getHeaders());
        }

        // Extract and propagate W3C Trace Context headers from HTTP request
        // This enables distributed tracing across the entire system
        String traceparent = ctx.request().getHeader("traceparent");
        if (traceparent != null && !traceparent.trim().isEmpty()) {
            headers.put("traceparent", traceparent);
            logger.debug("Propagating W3C traceparent: {}", traceparent);
        }

        String tracestate = ctx.request().getHeader("tracestate");
        if (tracestate != null && !tracestate.trim().isEmpty()) {
            headers.put("tracestate", tracestate);
            logger.debug("Propagating W3C tracestate: {}", tracestate);
        }

        String baggage = ctx.request().getHeader("baggage");
        if (baggage != null && !baggage.trim().isEmpty()) {
            headers.put("baggage", baggage);
            logger.debug("Propagating W3C baggage: {}", baggage);
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

        // Use correlation ID from request field, then headers, then generate one
        final String correlationId;
        if (request.getCorrelationId() != null && !request.getCorrelationId().trim().isEmpty()) {
            correlationId = request.getCorrelationId();
        } else if (headers.get("correlationId") != null) {
            correlationId = headers.get("correlationId");
        } else {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        // Use message group from request field, then headers
        final String messageGroup;
        if (request.getMessageGroup() != null && !request.getMessageGroup().trim().isEmpty()) {
            messageGroup = request.getMessageGroup();
        } else {
            messageGroup = headers.get("messageGroup");
        }

        // Extract idempotency key from HTTP header (Phase 2: Message Deduplication)
        // This enables clients to prevent duplicate message processing
        String idempotencyKey = ctx.request().getHeader("Idempotency-Key");
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            headers.put("idempotencyKey", idempotencyKey);
            logger.info("Sending message with idempotency key: {} (correlationId: {})",
                    idempotencyKey, correlationId);
        }

        // Send the message and return the correlation ID as the message ID
        return producer.send(request.getPayload(), headers, correlationId, messageGroup)
            .thenApply(v -> {
                if (idempotencyKey != null) {
                    logger.debug("Message sent successfully with idempotency key: {} (correlationId: {})",
                            idempotencyKey, correlationId);
                }
                return correlationId;
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

    // ===== PHASE 3: MESSAGE CONSUMPTION METHODS =====
    // REMOVED: Polling methods removed as per remediation plan Phase 1.2
    // The API now enforces webhook-based consumption only.

    /**
     * Request object for sending messages.
     */
    public static class MessageRequest {
        private Object payload;
        private Map<String, String> headers;
        private Integer priority;
        private Long delaySeconds;
        private String messageType; // Optional: specify expected type
        private String correlationId; // Optional: for distributed tracing
        private String messageGroup; // Optional: for ordered processing within a partition

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

        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

        public String getMessageGroup() { return messageGroup; }
        public void setMessageGroup(String messageGroup) { this.messageGroup = messageGroup; }

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
     * Check if this is a setup not found error (expected, no stack trace needed).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable != null &&
               throwable.getClass().getSimpleName().equals("SetupNotFoundException");
    }

    /**
     * Determines if an error is part of an intentional test scenario
     */
    private boolean isTestScenario(String setupId, Throwable throwable) {
        // Check for test setup IDs
        if (setupId != null && (setupId.equals("non-existent-setup") || setupId.startsWith("test-"))) {
            return true;
        }

        // Check for test-related error messages
        String message = throwable.getMessage();
        if (message != null && (message.contains("Setup not found: non-existent-setup") ||
                               message.contains("INTENTIONAL TEST FAILURE"))) {
            return true;
        }

        return false;
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
