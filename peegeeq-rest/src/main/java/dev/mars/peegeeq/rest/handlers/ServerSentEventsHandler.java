package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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
        if (!connection.isActive()) {
            return;
        }
        
        try {
            StringBuilder sseEvent = new StringBuilder();
            
            // Add event type if specified
            if (eventType != null && !eventType.isEmpty()) {
                sseEvent.append("event: ").append(eventType).append("\n");
            }
            
            // Add data
            sseEvent.append("data: ").append(data.encode()).append("\n");
            
            // Add empty line to complete the event
            sseEvent.append("\n");
            
            connection.getResponse().write(sseEvent.toString());
            connection.incrementMessagesSent();
            connection.updateActivity();
            
            logger.trace("Sent SSE event to connection {}: {}", connection.getConnectionId(), eventType);
            
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
        
        sendSSEEvent(connection, "message", dataEvent);
        connection.incrementMessagesReceived();
    }
    
    /**
     * Sends a batch of data events.
     */
    private void sendBatchEvent(SSEConnection connection, JsonObject[] messages) {
        JsonObject batchEvent = new JsonObject()
            .put("type", "batch")
            .put("connectionId", connection.getConnectionId())
            .put("messageCount", messages.length)
            .put("messages", messages)
            .put("timestamp", System.currentTimeMillis());
        
        sendSSEEvent(connection, "batch", batchEvent);
        connection.addMessagesReceived(messages.length);
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
                    
                    // Implement message streaming from consumer
                    // Note: This is a simplified implementation. A production version would:
                    // 1. Subscribe to the consumer's message stream using consumer.subscribe()
                    // 2. Apply filters if configured (message type, headers, content)
                    // 3. Send messages to SSE client in real-time with proper batching
                    // 4. Handle consumer group coordination and partition assignment
                    // 5. Send periodic heartbeats and connection statistics
                    // 6. Handle backpressure and flow control

                    // For now, we'll start a heartbeat timer to keep the connection alive
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
