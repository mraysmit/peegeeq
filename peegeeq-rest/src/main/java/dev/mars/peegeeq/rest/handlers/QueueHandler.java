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
import dev.mars.peegeeq.api.messaging.TopicNameValidator;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.rest.dto.BatchMessageRequest;
import dev.mars.peegeeq.rest.dto.MessageRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        if (!TopicNameValidator.isValid(queueName)) {
            ctx.response().setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Invalid queue name").encode());
            return;
        }

        // Set MDC for distributed tracing
        TraceContextUtil.setMDC(TraceContextUtil.MDC_SETUP_ID, setupId);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_QUEUE_NAME, queueName);

        // Extract and set trace context from W3C traceparent header
        // CRITICAL: Store in Vert.x Context (source of truth) AND MDC
        String traceparent = ctx.request().getHeader("traceparent");
        TraceCtx traceCtx = TraceContextUtil.parseOrCreate(traceparent);
        Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, traceCtx);
        TraceContextUtil.setMDCFromTraceparent(traceCtx.traceparent());

        try {
            // Parse and validate the message request
            MessageRequest messageRequest = parseAndValidateRequest(ctx);

            // Set correlation ID in MDC (will be set in sendMessageWithProducer)
            logger.info("Sending message to queue {} in setup: {}", queueName, setupId);

            // Get queue factory and send message
            getQueueFactory(setupId, queueName)
                .compose(queueFactory -> {
                    // Create producer for the message type
                    MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);

                    // Send the message
                    return sendMessageWithProducer(producer, messageRequest, ctx)
                        .eventually(() -> {
                            try {
                                producer.close();
                            } catch (Exception e) {
                                logger.warn("Error closing producer: {}", e.getMessage());
                            }
                            return Future.succeededFuture();
                        });
                })
                .map(messageId -> {
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, messageId);
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, messageId);
                    String detectedType = messageRequest.detectMessageType();
                    JsonObject response = new JsonObject()
                            .put("message", "Message sent successfully to queue '" + queueName + "' in setup '" + setupId + "'")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("messageId", messageId)
                            .put("correlationId", messageId)
                            .put("timestamp", System.currentTimeMillis())
                            .put("messageType", detectedType)
                            .put("priority", messageRequest.getPriority())
                            .put("delaySeconds", messageRequest.getDelaySeconds());
                    if (messageRequest.getMessageGroup() != null) {
                        response.put("messageGroup", messageRequest.getMessageGroup());
                    }
                    if (messageRequest.getHeaders() != null) {
                        response.put("customHeadersCount", messageRequest.getHeaders().size());
                    }
                    logger.info("Message sent successfully to queue {} in setup {} with ID: {} (type: {})",
                        queueName, setupId, messageId, detectedType);
                    return response;
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else if (isSetupNotFoundError(throwable)) {
                        logger.debug("Setup not found for queue operation: {} (setup: {})", queueName, setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error sending message to queue: {}", queueName, throwable);
                        sendError(ctx, 503, "Failed to send message to queue '" + queueName + "' in setup '" + setupId + "': " + throwable.getMessage());
                    }
                })
                .eventually(() -> {
                    TraceContextUtil.clearTraceMDC();
                    return Future.succeededFuture();
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

        if (!TopicNameValidator.isValid(queueName)) {
            ctx.response().setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Invalid queue name").encode());
            return;
        }

        // Set MDC for distributed tracing
        TraceContextUtil.setMDC(TraceContextUtil.MDC_SETUP_ID, setupId);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_QUEUE_NAME, queueName);

        // Extract and set trace context from W3C traceparent header
        // CRITICAL: Store in Vert.x Context (source of truth) AND MDC
        String traceparent = ctx.request().getHeader("traceparent");
        TraceCtx traceCtx = TraceContextUtil.parseOrCreate(traceparent);
        Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, traceCtx);
        TraceContextUtil.setMDCFromTraceparent(traceCtx.traceparent());

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
                .compose(queueFactory -> {
                    MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);

                    // Send all messages (propagate same trace context to all messages in batch)
                    List<Future<String>> futures = batchRequest.getMessages().stream()
                        .map(msgReq -> sendMessageWithProducer(producer, msgReq, ctx)
                            .transform(ar -> {
                                if (ar.failed()) {
                                    if (batchRequest.isFailOnError()) {
                                        return Future.<String>failedFuture(new RuntimeException("Batch failed at message: " + ar.cause().getMessage(), ar.cause()));
                                    } else {
                                        logger.error("Failed to send message in batch: {}", ar.cause().getMessage());
                                        return Future.succeededFuture("FAILED:" + ar.cause().getMessage());
                                    }
                                }
                                return Future.succeededFuture(ar.result());
                            }))
                        .collect(Collectors.toList());

                    return Future.all(futures.stream().map(f -> (Future<?>) f).collect(Collectors.toList()))
                        .map(cf -> futures.stream()
                            .map(Future::result)
                            .collect(Collectors.toList()))
                        .eventually(() -> {
                            try {
                                producer.close();
                            } catch (Exception e) {
                                logger.warn("Error closing producer: {}", e.getMessage());
                            }
                            return Future.succeededFuture();
                        });
                })
                .map(messageIds -> {
                    long successCount = messageIds.stream().filter(id -> !id.startsWith("FAILED:")).count();
                    long failureCount = messageIds.size() - successCount;
                    logger.info("Batch processed: {} successful, {} failed for queue {} in setup {}",
                        successCount, failureCount, queueName, setupId);
                    return new JsonObject()
                            .put("message", "Batch of " + successCount + "/" + messageIds.size() + " messages sent successfully to queue '" + queueName + "' in setup '" + setupId + "'")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("totalMessages", messageIds.size())
                            .put("successfulMessages", successCount)
                            .put("failedMessages", failureCount)
                            .put("messageIds", messageIds);
                })
                .onSuccess(response -> {
                    int statusCode = response.getLong("failedMessages") > 0 ? 207 : 200;
                    ctx.response()
                            .setStatusCode(statusCode)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());
                })
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else if (isSetupNotFoundError(throwable)) {
                        logger.debug("Setup not found for batch queue operation: {} (setup: {})", queueName, setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error sending batch messages to queue: {}", queueName, throwable);
                        sendError(ctx, 503, "Failed to send batch messages: " + throwable.getMessage());
                    }
                })
                .eventually(() -> {
                    TraceContextUtil.clearTraceMDC();
                    return Future.succeededFuture();
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

        if (!TopicNameValidator.isValid(queueName)) {
            ctx.response().setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Invalid queue name").encode());
            return;
        }

        logger.info("Getting stats for queue {} in setup: {}", queueName, setupId);

        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    return Future.succeededFuture(queueFactory);
                })
                .compose(queueFactory -> {
                    String implementationType = queueFactory.getImplementationType();
                    return queueFactory.isHealthy()
                            .compose(isHealthy -> queueFactory.getStats(queueName)
                                    .map(stats -> {
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
                                        if (stats.getCreatedAt() != null) {
                                            response.put("firstMessageAt", stats.getCreatedAt().toString());
                                        }
                                        if (stats.getLastMessageAt() != null) {
                                            response.put("lastMessageAt", stats.getLastMessageAt().toString());
                                        }
                                        logger.info("Retrieved stats for queue {} (type: {}, healthy: {}, total: {}, pending: {})",
                                                queueName, implementationType, isHealthy,
                                                stats.getTotalMessages(), stats.getPendingMessages());
                                        return response;
                                    }));
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isSetupNotFoundError(cause)) {
                            logger.debug("Setup not found for queue stats: {} (setup: {})", queueName, setupId);
                            sendError(ctx, 404, "Queue not found");
                        } else {
                            logger.error("Error getting queue stats for {}: {}", queueName, throwable.getMessage(), throwable);
                            sendError(ctx, 503, "Failed to get queue stats: " + throwable.getMessage());
                        }
                    }
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
    Future<QueueFactory> getQueueFactory(String setupId, String queueName) {
        return setupService.getSetupResult(setupId)
            .compose(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    return Future.failedFuture(new ResponseException(400, "Setup is not active: " + setupId));
                }
                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                }
                return Future.succeededFuture(queueFactory);
            });
    }

    /**
     * Sends a message using the MessageProducer with the appropriate headers and metadata.
     * Extracts W3C Trace Context headers from the HTTP request and propagates them to the message.
     */
    private Future<String> sendMessageWithProducer(MessageProducer<Object> producer, MessageRequest request, RoutingContext ctx) {
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

        // Add approximate JSON payload size for monitoring
        try {
            headers.put("payloadSize", String.valueOf(objectMapper.writeValueAsString(request.getPayload()).length()));
        } catch (Exception e) {
            headers.put("payloadSize", "unknown");
        }

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
            .map(v -> {
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

    /**
     * Check if this is a setup not found error (expected, no stack trace needed).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable != null &&
               throwable.getClass().getSimpleName().equals("SetupNotFoundException");
    }

}
