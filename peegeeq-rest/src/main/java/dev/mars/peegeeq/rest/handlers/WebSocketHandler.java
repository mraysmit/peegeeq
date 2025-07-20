package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket handler for real-time message streaming.
 * 
 * Provides WebSocket endpoints for streaming messages in real-time from PeeGeeQ queues.
 * Supports connection management, subscription handling, and proper cleanup.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class WebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);
    
    private final DatabaseSetupService setupService;
    // Connection management
    private final Map<String, WebSocketConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);
    
    public WebSocketHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
    }
    
    /**
     * Handles WebSocket connections for queue message streaming.
     * WebSocket URL: /ws/queues/{setupId}/{queueName}
     */
    public void handleQueueStream(ServerWebSocket webSocket) {
        String setupId = webSocket.path().split("/")[3]; // /ws/queues/{setupId}/{queueName}
        String queueName = webSocket.path().split("/")[4];
        String connectionId = "ws-" + connectionIdCounter.incrementAndGet();
        
        logger.info("WebSocket connection established: {} for queue {} in setup {}", 
                   connectionId, queueName, setupId);
        
        // Create connection wrapper
        WebSocketConnection connection = new WebSocketConnection(connectionId, webSocket, setupId, queueName);
        activeConnections.put(connectionId, connection);
        
        // Send welcome message
        sendWelcomeMessage(webSocket, connectionId, setupId, queueName);
        
        // Set up message handlers
        webSocket.textMessageHandler(message -> handleTextMessage(connection, message));
        webSocket.binaryMessageHandler(buffer -> handleBinaryMessage(connection, buffer));
        webSocket.exceptionHandler(throwable -> handleWebSocketError(connection, throwable));
        webSocket.closeHandler(v -> handleWebSocketClose(connection));
        
        // Start streaming messages
        startMessageStreaming(connection);
    }
    
    /**
     * Sends a welcome message to the newly connected WebSocket client.
     */
    private void sendWelcomeMessage(ServerWebSocket webSocket, String connectionId, String setupId, String queueName) {
        JsonObject welcome = new JsonObject()
            .put("type", "welcome")
            .put("connectionId", connectionId)
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("timestamp", System.currentTimeMillis())
            .put("message", "Connected to PeeGeeQ WebSocket stream");
        
        webSocket.writeTextMessage(welcome.encode());
    }
    
    /**
     * Handles incoming text messages from WebSocket clients.
     */
    private void handleTextMessage(WebSocketConnection connection, String message) {
        logger.debug("Received WebSocket text message from {}: {}", connection.getConnectionId(), message);
        
        try {
            JsonObject messageObj = new JsonObject(message);
            String type = messageObj.getString("type");
            
            switch (type) {
                case "ping":
                    handlePingMessage(connection, messageObj);
                    break;
                case "subscribe":
                    handleSubscribeMessage(connection, messageObj);
                    break;
                case "unsubscribe":
                    handleUnsubscribeMessage(connection, messageObj);
                    break;
                case "configure":
                    handleConfigureMessage(connection, messageObj);
                    break;
                default:
                    sendErrorMessage(connection, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.error("Error processing WebSocket message from {}: {}", connection.getConnectionId(), message, e);
            sendErrorMessage(connection, "Invalid message format: " + e.getMessage());
        }
    }
    
    /**
     * Handles incoming binary messages from WebSocket clients.
     */
    private void handleBinaryMessage(WebSocketConnection connection, io.vertx.core.buffer.Buffer buffer) {
        logger.debug("Received WebSocket binary message from {}: {} bytes", 
                    connection.getConnectionId(), buffer.length());
        
        // For now, we don't support binary messages
        sendErrorMessage(connection, "Binary messages are not supported");
    }
    
    /**
     * Handles ping messages for connection keep-alive.
     */
    private void handlePingMessage(WebSocketConnection connection, JsonObject message) {
        JsonObject pong = new JsonObject()
            .put("type", "pong")
            .put("connectionId", connection.getConnectionId())
            .put("timestamp", System.currentTimeMillis());
        
        if (message.containsKey("id")) {
            pong.put("id", message.getValue("id"));
        }
        
        connection.getWebSocket().writeTextMessage(pong.encode());
    }
    
    /**
     * Handles subscription configuration messages.
     */
    private void handleSubscribeMessage(WebSocketConnection connection, JsonObject message) {
        logger.info("WebSocket {} subscribing with config: {}", connection.getConnectionId(), message);
        
        // Extract subscription parameters
        String consumerGroup = message.getString("consumerGroup");
        JsonObject filters = message.getJsonObject("filters");
        
        connection.setConsumerGroup(consumerGroup);
        connection.setFilters(filters);
        
        // Send confirmation
        JsonObject confirmation = new JsonObject()
            .put("type", "subscribed")
            .put("connectionId", connection.getConnectionId())
            .put("consumerGroup", consumerGroup)
            .put("filters", filters)
            .put("timestamp", System.currentTimeMillis());
        
        connection.getWebSocket().writeTextMessage(confirmation.encode());
    }
    
    /**
     * Handles unsubscription messages.
     */
    private void handleUnsubscribeMessage(WebSocketConnection connection, JsonObject message) {
        logger.info("WebSocket {} unsubscribing", connection.getConnectionId());
        
        connection.setActive(false);
        
        JsonObject confirmation = new JsonObject()
            .put("type", "unsubscribed")
            .put("connectionId", connection.getConnectionId())
            .put("timestamp", System.currentTimeMillis());
        
        connection.getWebSocket().writeTextMessage(confirmation.encode());
    }
    
    /**
     * Handles configuration messages.
     */
    private void handleConfigureMessage(WebSocketConnection connection, JsonObject message) {
        logger.debug("WebSocket {} configuration: {}", connection.getConnectionId(), message);
        
        // Update connection configuration
        if (message.containsKey("batchSize")) {
            connection.setBatchSize(message.getInteger("batchSize", 1));
        }
        
        if (message.containsKey("maxWaitTime")) {
            connection.setMaxWaitTime(message.getLong("maxWaitTime", 5000L));
        }
        
        JsonObject confirmation = new JsonObject()
            .put("type", "configured")
            .put("connectionId", connection.getConnectionId())
            .put("batchSize", connection.getBatchSize())
            .put("maxWaitTime", connection.getMaxWaitTime())
            .put("timestamp", System.currentTimeMillis());
        
        connection.getWebSocket().writeTextMessage(confirmation.encode());
    }
    
    /**
     * Sends an error message to the WebSocket client.
     */
    private void sendErrorMessage(WebSocketConnection connection, String errorMessage) {
        JsonObject error = new JsonObject()
            .put("type", "error")
            .put("connectionId", connection.getConnectionId())
            .put("error", errorMessage)
            .put("timestamp", System.currentTimeMillis());
        
        connection.getWebSocket().writeTextMessage(error.encode());
    }
    
    /**
     * Handles WebSocket errors.
     */
    private void handleWebSocketError(WebSocketConnection connection, Throwable throwable) {
        logger.error("WebSocket error for connection {}: {}", connection.getConnectionId(), throwable.getMessage(), throwable);
        
        sendErrorMessage(connection, "WebSocket error: " + throwable.getMessage());
    }
    
    /**
     * Handles WebSocket connection close.
     */
    private void handleWebSocketClose(WebSocketConnection connection) {
        logger.info("WebSocket connection closed: {}", connection.getConnectionId());
        
        // Clean up resources
        connection.cleanup();
        activeConnections.remove(connection.getConnectionId());
        
        logger.debug("WebSocket connection {} cleaned up. Active connections: {}", 
                    connection.getConnectionId(), activeConnections.size());
    }
    
    /**
     * Starts streaming messages to the WebSocket connection.
     */
    private void startMessageStreaming(WebSocketConnection connection) {
        logger.info("Starting message streaming for WebSocket connection: {}", connection.getConnectionId());
        
        setupService.getSetupResult(connection.getSetupId())
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendErrorMessage(connection, "Setup " + connection.getSetupId() + " is not active");
                    return;
                }
                
                QueueFactory queueFactory = setupResult.getQueueFactories().get(connection.getQueueName());
                if (queueFactory == null) {
                    sendErrorMessage(connection, "Queue " + connection.getQueueName() + " not found in setup " + connection.getSetupId());
                    return;
                }
                
                try {
                    // Create consumer for streaming
                    MessageConsumer<Object> consumer = queueFactory.createConsumer(connection.getQueueName(), Object.class);
                    connection.setConsumer(consumer);
                    
                    // TODO: Implement actual message streaming from consumer
                    // This is a placeholder - in a real implementation, we would:
                    // 1. Subscribe to the consumer's message stream
                    // 2. Apply filters if configured
                    // 3. Send messages to WebSocket in real-time
                    // 4. Handle consumer group coordination
                    
                    logger.info("Message streaming started for WebSocket connection: {}", connection.getConnectionId());
                    
                } catch (Exception e) {
                    logger.error("Error starting message streaming for connection {}: {}", connection.getConnectionId(), e.getMessage(), e);
                    sendErrorMessage(connection, "Failed to start message streaming: " + e.getMessage());
                }
            })
            .exceptionally(throwable -> {
                logger.error("Error setting up message streaming for connection {}: {}", connection.getConnectionId(), throwable.getMessage(), throwable);
                sendErrorMessage(connection, "Failed to setup message streaming: " + throwable.getMessage());
                return null;
            });
    }
    
    /**
     * Gets the number of active WebSocket connections.
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
