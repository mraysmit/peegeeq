package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.TopicNameValidator;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Future;
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
 * Provides WebSocket endpoints for streaming messages in real-time from PeeGeeQ
 * queues.
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

        if (!TopicNameValidator.isValid(queueName)) {
            webSocket.close((short) 4000, "Invalid queue name");
            return;
        }

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
        logger.error("WebSocket error for connection {}: {}", connection.getConnectionId(), throwable.getMessage(),
                throwable);

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
        logger.info("Starting non-destructive message streaming for WebSocket connection: {}",
                connection.getConnectionId());

        // NON-DESTRUCTIVE by design. The management UI is an admin/observability tool and must never
        // consume/ack messages just to display them — doing so would steal them from the application's
        // real consumers. This mirrors the SSE stream (ServerSentEventsHandler.handleQueueMessageStream):
        // it opens a QueueBrowser.tail() (LISTEN/NOTIFY + browse → plain SELECT) and pushes each newly
        // observed message. It NEVER calls createConsumer/subscribe.
        setupService.getSetupResult(connection.getSetupId())
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.<QueueBrowser<Object>>failedFuture(
                                new IllegalStateException("Setup " + connection.getSetupId() + " is not active"));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(connection.getQueueName());
                    if (queueFactory == null) {
                        return Future.<QueueBrowser<Object>>failedFuture(new IllegalArgumentException(
                                "Queue " + connection.getQueueName() + " not found in setup " + connection.getSetupId()));
                    }
                    return queueFactory.createBrowser(connection.getQueueName(), Object.class);
                })
                .compose(browser -> {
                    // If the client already disconnected, release the browser and stop.
                    if (connection.getWebSocket().isClosed()) {
                        try { browser.close(); }
                        catch (Exception e) {
                            logger.warn("Error closing tail browser for {}: {}",
                                    connection.getConnectionId(), e.getMessage());
                        }
                        return Future.<Void>succeededFuture();
                    }
                    connection.setBrowser(browser);  // closed by WebSocketConnection.cleanup() on disconnect
                    // Live observe: tail pushes each new message; it never consumes/acks.
                    return browser.tail(message -> {
                        connection.sendDataMessage(
                                message.getPayload(), String.valueOf(message.getId()), null, null);
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> {
                    connection.setSubscribed(true);
                    logger.info("WebSocket queue stream {} observing queue '{}' (non-destructive tail established)",
                            connection.getConnectionId(), connection.getQueueName());
                    JsonObject subscribed = new JsonObject()
                            .put("type", "subscribed")
                            .put("connectionId", connection.getConnectionId())
                            .put("queueName", connection.getQueueName())
                            .put("timestamp", System.currentTimeMillis());
                    connection.getWebSocket().writeTextMessage(subscribed.encode());
                })
                .onFailure(throwable -> {
                    logger.error("Error setting up non-destructive message streaming for connection {}: {}",
                            connection.getConnectionId(), throwable.getMessage(), throwable);
                    sendErrorMessage(connection, "Failed to setup message streaming: " + throwable.getMessage());
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

    /**
     * Closes all active WebSocket connections and cleans up resources.
     * Should be called during server shutdown.
     */
    public void close() {
        logger.info("Closing WebSocketHandler and {} active connections", activeConnections.size());

        activeConnections.values().forEach(connection -> {
            try {
                connection.cleanup();
                if (!connection.getWebSocket().isClosed()) {
                    connection.getWebSocket().close((short) 1001, "Server shutting down");
                }
            } catch (Exception e) {
                logger.warn("Error closing WebSocket connection {}: {}", connection.getConnectionId(), e.getMessage());
            }
        });

        activeConnections.clear();
        logger.info("WebSocketHandler closed");
    }
}
