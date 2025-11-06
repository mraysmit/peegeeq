package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events (SSE) handler for real-time message streaming.
 * 
 * Provides SSE endpoints for streaming messages in real-time from PeeGeeQ queues
 * using standard HTTP connections. SSE is ideal for one-way server-to-client
 * real-time communication.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ServerSentEventsHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerSentEventsHandler.class);
    
    private final DatabaseSetupService setupService;
    private final Vertx vertx;
    
    // Connection management
    private final Map<String, SSEConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);
    
    public ServerSentEventsHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, Vertx vertx) {
        this.setupService = setupService;
        this.vertx = vertx;
    }
    
    /**
     * Handles SSE connections for queue message streaming.
     * SSE URL: GET /api/v1/queues/{setupId}/{queueName}/stream
     */
    public void handleQueueStream(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String connectionId = "sse-" + connectionIdCounter.incrementAndGet();
        
        logger.info("SSE connection established: {} for queue {} in setup {}", 
                   connectionId, queueName, setupId);
        
        // Set up SSE headers
        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Headers", "Cache-Control")
                .setChunked(true);
        
        // Create SSE connection wrapper
        SSEConnection connection = new SSEConnection(connectionId, response, setupId, queueName);
        activeConnections.put(connectionId, connection);

        // Check for Last-Event-ID header (SSE reconnection)
        String lastEventId = ctx.request().getHeader("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            connection.setResumeFromMessageId(lastEventId);
            logger.info("SSE reconnection detected for connection {}, Last-Event-ID: {}",
                       connectionId, lastEventId);
        }

        // Handle connection close
        ctx.request().connection().closeHandler(v -> handleConnectionClose(connection));

        // Send initial connection event
        sendConnectionEvent(connection);

        // Parse query parameters for configuration
        parseConnectionParameters(ctx, connection);

        // Start streaming messages
        startMessageStreaming(connection);
    }
    
    /**
     * Parses query parameters to configure the SSE connection.
     */
    private void parseConnectionParameters(RoutingContext ctx, SSEConnection connection) {
        // Parse consumer group
        String consumerGroup = ctx.request().getParam("consumerGroup");
        if (consumerGroup != null && !consumerGroup.trim().isEmpty()) {
            connection.setConsumerGroup(consumerGroup);
        }
        
        // Parse batch size
        String batchSizeParam = ctx.request().getParam("batchSize");
        if (batchSizeParam != null) {
            try {
                int batchSize = Integer.parseInt(batchSizeParam);
                connection.setBatchSize(batchSize);
            } catch (NumberFormatException e) {
                logger.warn("Invalid batchSize parameter for SSE connection {}: {}", connection.getConnectionId(), batchSizeParam);
            }
        }
        
        // Parse max wait time
        String maxWaitParam = ctx.request().getParam("maxWait");
        if (maxWaitParam != null) {
            try {
                long maxWait = Long.parseLong(maxWaitParam);
                connection.setMaxWaitTime(maxWait);
            } catch (NumberFormatException e) {
                logger.warn("Invalid maxWait parameter for SSE connection {}: {}", connection.getConnectionId(), maxWaitParam);
            }
        }
        
        // Parse message type filter
        String messageTypeFilter = ctx.request().getParam("messageType");
        if (messageTypeFilter != null && !messageTypeFilter.trim().isEmpty()) {
            JsonObject filters = new JsonObject().put("messageType", messageTypeFilter);
            connection.setFilters(filters);
        }
        
        // Parse header filters (format: header.key=value)
        JsonObject headerFilters = new JsonObject();
        ctx.request().params().forEach(param -> {
            if (param.getKey().startsWith("header.")) {
                String headerKey = param.getKey().substring(7); // Remove "header." prefix
                headerFilters.put(headerKey, param.getValue());
            }
        });
        
        if (!headerFilters.isEmpty()) {
            JsonObject filters = connection.getFilters();
            if (filters == null) {
                filters = new JsonObject();
                connection.setFilters(filters);
            }
            filters.put("headers", headerFilters);
        }
        
        logger.debug("SSE connection {} configured: consumerGroup={}, batchSize={}, maxWait={}, filters={}", 
                    connection.getConnectionId(), connection.getConsumerGroup(), 
                    connection.getBatchSize(), connection.getMaxWaitTime(), connection.getFilters());
    }
    
    /**
     * Sends the initial connection event to the SSE client.
     */
    private void sendConnectionEvent(SSEConnection connection) {
        JsonObject connectionInfo = new JsonObject()
            .put("type", "connection")
            .put("connectionId", connection.getConnectionId())
            .put("setupId", connection.getSetupId())
            .put("queueName", connection.getQueueName())
            .put("timestamp", System.currentTimeMillis())
            .put("message", "Connected to PeeGeeQ SSE stream");
        
        sendSSEEvent(connection, "connection", connectionInfo);
    }
    
    /**
     * Sends an SSE event to the client.
     */
    private void sendSSEEvent(SSEConnection connection, String eventType, JsonObject data) {
        sendSSEEvent(connection, eventType, data, null);
    }

    /**
     * Sends an SSE event with optional message ID for reconnection support.
     */
    private void sendSSEEvent(SSEConnection connection, String eventType, JsonObject data, String messageId) {
        if (!connection.isActive()) {
            return;
        }

        try {
            StringBuilder sseEvent = new StringBuilder();

            // Add event type if specified
            if (eventType != null && !eventType.isEmpty()) {
                sseEvent.append("event: ").append(eventType).append("\n");
            }

            // Add message ID for reconnection (SSE standard)
            if (messageId != null && !messageId.isEmpty()) {
                sseEvent.append("id: ").append(messageId).append("\n");
            }

            // Add data
            sseEvent.append("data: ").append(data.encode()).append("\n");

            // Add empty line to complete the event
            sseEvent.append("\n");

            connection.getResponse().write(sseEvent.toString());
            connection.incrementMessagesSent();
            connection.updateActivity();

            logger.trace("Sent SSE event to connection {}: {} (id: {})", connection.getConnectionId(), eventType, messageId);

        } catch (Exception e) {
            logger.error("Error sending SSE event to connection {}: {}", connection.getConnectionId(), e.getMessage(), e);
            connection.setActive(false);
        }
    }
    
    /**
     * Sends a data event containing a queue message.
     */
    private void sendDataEvent(SSEConnection connection, Object payload, String messageId, JsonObject headers, String messageType) {
        JsonObject dataEvent = new JsonObject()
            .put("type", "data")
            .put("connectionId", connection.getConnectionId())
            .put("messageId", messageId)
            .put("payload", payload)
            .put("messageType", messageType)
            .put("timestamp", System.currentTimeMillis());

        if (headers != null && !headers.isEmpty()) {
            dataEvent.put("headers", headers);
        }

        // Send with message ID for SSE reconnection support
        sendSSEEvent(connection, "message", dataEvent, messageId);
        connection.incrementMessagesReceived();
    }
    
    /**
     * Adds a message to the batch buffer or sends it immediately if batching is disabled.
     * Handles batch timeout and automatic flushing when batch is full.
     */
    private void addMessageToBatch(SSEConnection connection, Object payload, String messageId,
                                   JsonObject headers, String messageType) {
        int batchSize = connection.getBatchSize();

        // If batch size is 1, send immediately (no batching)
        if (batchSize == 1) {
            sendDataEvent(connection, payload, messageId, headers, messageType);
            return;
        }

        // Build message object for batch
        JsonObject messageData = new JsonObject()
            .put("messageId", messageId)
            .put("payload", payload)
            .put("messageType", messageType)
            .put("headers", headers)
            .put("timestamp", System.currentTimeMillis());

        // Add to batch buffer
        List<JsonObject> batchBuffer = connection.getBatchBuffer();
        batchBuffer.add(messageData);
        connection.setLastMessageIdInBatch(messageId);

        logger.trace("Added message {} to batch for connection {} ({}/{})",
                    messageId, connection.getConnectionId(), batchBuffer.size(), batchSize);

        // If batch is full, send it immediately
        if (batchBuffer.size() >= batchSize) {
            logger.debug("Batch full for connection {}, sending {} messages",
                        connection.getConnectionId(), batchBuffer.size());
            cancelBatchTimer(connection);
            sendBatchEvent(connection);
        } else {
            // Start or reset batch timeout timer
            startBatchTimer(connection);
        }
    }

    /**
     * Starts or resets the batch timeout timer.
     * When the timer expires, the batch is sent even if not full.
     */
    private void startBatchTimer(SSEConnection connection) {
        // Cancel existing timer if any
        cancelBatchTimer(connection);

        // Set new timer
        long timerId = vertx.setTimer(connection.getMaxWaitTime(), id -> {
            logger.debug("Batch timeout for connection {}, sending {} messages",
                        connection.getConnectionId(), connection.getBatchBuffer().size());
            sendBatchEvent(connection);
        });

        connection.setBatchTimerId(timerId);
    }

    /**
     * Cancels the batch timeout timer if it exists.
     */
    private void cancelBatchTimer(SSEConnection connection) {
        Long timerId = connection.getBatchTimerId();
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            connection.setBatchTimerId(null);
        }
    }

    /**
     * Sends a batch of data events.
     * Uses the last message ID in the batch for the SSE event ID field.
     */
    private void sendBatchEvent(SSEConnection connection) {
        List<JsonObject> batchBuffer = connection.getBatchBuffer();

        if (batchBuffer.isEmpty()) {
            return;
        }

        // Build batch event with all messages
        JsonObject batchEvent = new JsonObject()
            .put("type", "batch")
            .put("connectionId", connection.getConnectionId())
            .put("messageCount", batchBuffer.size())
            .put("messages", new io.vertx.core.json.JsonArray(batchBuffer))
            .put("timestamp", System.currentTimeMillis());

        // Use the last message ID in the batch for the SSE event ID
        String lastMessageId = connection.getLastMessageIdInBatch();

        logger.debug("Sending batch of {} messages for connection {}, last message ID: {}",
                    batchBuffer.size(), connection.getConnectionId(), lastMessageId);

        // Send the batch event
        sendSSEEvent(connection, "batch", batchEvent, lastMessageId);

        // Update statistics
        connection.addMessagesReceived(batchBuffer.size());

        // Clear the batch buffer
        batchBuffer.clear();
        connection.setLastMessageIdInBatch(null);
    }
    
    /**
     * Sends a heartbeat event to keep the connection alive.
     */
    private void sendHeartbeatEvent(SSEConnection connection) {
        JsonObject heartbeat = new JsonObject()
            .put("type", "heartbeat")
            .put("connectionId", connection.getConnectionId())
            .put("timestamp", System.currentTimeMillis())
            .put("messagesReceived", connection.getMessagesReceived())
            .put("messagesSent", connection.getMessagesSent())
            .put("uptime", System.currentTimeMillis() - connection.getCreatedAt());
        
        sendSSEEvent(connection, "heartbeat", heartbeat);
    }
    
    /**
     * Sends an error event to the client.
     */
    private void sendErrorEvent(SSEConnection connection, String errorMessage) {
        JsonObject error = new JsonObject()
            .put("type", "error")
            .put("connectionId", connection.getConnectionId())
            .put("error", errorMessage)
            .put("timestamp", System.currentTimeMillis());
        
        sendSSEEvent(connection, "error", error);
    }
    
    /**
     * Handles SSE connection close.
     */
    private void handleConnectionClose(SSEConnection connection) {
        logger.info("SSE connection closed: {}", connection.getConnectionId());

        // Cancel batch timer if active
        cancelBatchTimer(connection);

        // Flush any pending batch messages
        if (!connection.getBatchBuffer().isEmpty()) {
            logger.debug("Flushing {} pending batch messages for closing connection {}",
                        connection.getBatchBuffer().size(), connection.getConnectionId());
            sendBatchEvent(connection);
        }

        // Clean up resources
        connection.cleanup();
        activeConnections.remove(connection.getConnectionId());

        logger.debug("SSE connection {} cleaned up. Active connections: {}",
                    connection.getConnectionId(), activeConnections.size());
    }
    
    /**
     * Starts streaming messages to the SSE connection.
     */
    private void startMessageStreaming(SSEConnection connection) {
        logger.info("Starting message streaming for SSE connection: {}", connection.getConnectionId());
        
        setupService.getSetupResult(connection.getSetupId())
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendErrorEvent(connection, "Setup " + connection.getSetupId() + " is not active");
                    return;
                }
                
                QueueFactory queueFactory = setupResult.getQueueFactories().get(connection.getQueueName());
                if (queueFactory == null) {
                    sendErrorEvent(connection, "Queue " + connection.getQueueName() + " not found in setup " + connection.getSetupId());
                    return;
                }
                
                try {
                    // Create consumer for streaming
                    MessageConsumer<Object> consumer = queueFactory.createConsumer(connection.getQueueName(), Object.class);
                    connection.setConsumer(consumer);

                    // Subscribe to messages from the queue
                    consumer.subscribe(message -> {
                        try {
                            // Handle SSE reconnection - skip messages until we reach the resume point
                            String resumeFrom = connection.getResumeFromMessageId();
                            if (resumeFrom != null && !connection.isResumePointReached()) {
                                if (message.getId().equals(resumeFrom)) {
                                    // Found the resume point, mark it and start sending from the NEXT message
                                    connection.setResumePointReached(true);
                                    logger.info("SSE connection {} reached resume point at message {}",
                                               connection.getConnectionId(), resumeFrom);
                                    return CompletableFuture.completedFuture(null);
                                } else {
                                    // Skip this message, we haven't reached the resume point yet
                                    logger.trace("SSE connection {} skipping message {} (waiting for {})",
                                                connection.getConnectionId(), message.getId(), resumeFrom);
                                    return CompletableFuture.completedFuture(null);
                                }
                            }

                            // Get messageType from headers (stored by REST API when message was sent)
                            String messageType = message.getHeaders() != null ?
                                message.getHeaders().get("messageType") : "Unknown";

                            // Convert headers Map<String, String> to JsonObject for SSE event
                            JsonObject headersJson = headersToJsonObject(message.getHeaders());

                            // Apply filters if configured (messageType, headers, content)
                            if (!connection.shouldSendMessage(message.getPayload(), headersJson, messageType)) {
                                // Message filtered out, skip it
                                return CompletableFuture.completedFuture(null);
                            }

                            // Add message to batch or send immediately
                            // Note: message.getPayload() is already deserialized by the consumer
                            // addMessageToBatch() handles batching logic and calls sendDataEvent() or sendBatchEvent()
                            addMessageToBatch(connection, message.getPayload(), message.getId(),
                                            headersJson, messageType);

                            return CompletableFuture.completedFuture(null);

                        } catch (Exception e) {
                            logger.error("Error processing message for SSE connection {}: {}",
                                        connection.getConnectionId(), e.getMessage(), e);
                            sendErrorEvent(connection, "Error processing message: " + e.getMessage());
                            return CompletableFuture.failedFuture(e);
                        }
                    });

                    // Start heartbeat timer to keep the connection alive
                    startHeartbeatTimer(connection);

                    logger.info("Message streaming started for SSE connection: {}", connection.getConnectionId());

                    // Send configuration confirmation
                    JsonObject configEvent = new JsonObject()
                        .put("type", "configured")
                        .put("connectionId", connection.getConnectionId())
                        .put("consumerGroup", connection.getConsumerGroup())
                        .put("batchSize", connection.getBatchSize())
                        .put("maxWaitTime", connection.getMaxWaitTime())
                        .put("filters", connection.getFilters())
                        .put("timestamp", System.currentTimeMillis());

                    sendSSEEvent(connection, "configured", configEvent);

                } catch (Exception e) {
                    logger.error("Error starting message streaming for SSE connection {}: {}", connection.getConnectionId(), e.getMessage(), e);
                    sendErrorEvent(connection, "Failed to start message streaming: " + e.getMessage());
                }
            })
            .exceptionally(throwable -> {
                logger.error("Error setting up message streaming for SSE connection {}: {}", connection.getConnectionId(), throwable.getMessage(), throwable);
                sendErrorEvent(connection, "Failed to setup message streaming: " + throwable.getMessage());
                return null;
            });
    }
    
    /**
     * Starts a heartbeat timer for the SSE connection.
     */
    private void startHeartbeatTimer(SSEConnection connection) {
        // Schedule periodic heartbeats to keep the connection alive
        vertx.setPeriodic(30000, timerId -> { // Every 30 seconds
            if (activeConnections.containsKey(connection.getConnectionId())) {
                sendHeartbeatEvent(connection);
            } else {
                // Connection is no longer active, cancel the timer
                vertx.cancelTimer(timerId);
            }
        });
    }

    /**
     * Convert headers Map<String, String> to JsonObject for SSE events.
     * Follows the established pattern from peegeeq-native and peegeeq-outbox modules.
     */
    private JsonObject headersToJsonObject(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return new JsonObject();
        // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
        Map<String, Object> objectMap = new HashMap<>(headers);
        return new JsonObject(objectMap);
    }

    /**
     * Gets the number of active SSE connections.
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }
    
    /**
     * Gets information about active connections.
     */
    public JsonObject getConnectionInfo() {
        JsonObject info = new JsonObject()
            .put("activeConnections", activeConnections.size())
            .put("timestamp", System.currentTimeMillis());
        
        return info;
    }
}
