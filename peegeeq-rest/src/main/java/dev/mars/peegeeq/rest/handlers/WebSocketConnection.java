package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.QueueBrowser;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an active WebSocket connection for real-time message streaming.
 * 
 * Manages the state and configuration of a single WebSocket connection,
 * including subscription settings, filters, and the non-destructive queue browser it observes.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class WebSocketConnection {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSocketConnection.class);
    
    private final String connectionId;
    private final ServerWebSocket webSocket;
    private final String setupId;
    private final String queueName;
    private final long createdAt;
    
    // Connection state
    private volatile boolean active = true;
    private volatile boolean subscribed = false;
    
    // Subscription configuration
    private String consumerGroup;
    private JsonObject filters;
    private int batchSize = 1;
    private long maxWaitTime = 5000L; // 5 seconds default
    
    // Non-destructive live observer (QueueBrowser.tail()). Never consumes/acks messages.
    private QueueBrowser<Object> browser;
    
    // Statistics
    private volatile long messagesReceived = 0;
    private volatile long messagesSent = 0;
    private volatile long lastActivityTime;
    
    // Wall clock for the statistics timestamps.
    private final java.util.function.LongSupplier clock;

    public WebSocketConnection(String connectionId, ServerWebSocket webSocket, String setupId, String queueName) {
        this(connectionId, webSocket, setupId, queueName, System::currentTimeMillis);
    }

    WebSocketConnection(String connectionId, ServerWebSocket webSocket, String setupId, String queueName,
                        java.util.function.LongSupplier clock) {
        this.connectionId = connectionId;
        this.webSocket = webSocket;
        this.setupId = setupId;
        this.queueName = queueName;
        this.clock = clock;
        this.createdAt = clock.getAsLong();
        this.lastActivityTime = this.createdAt;
        
        logger.debug("Created WebSocket connection: {} for queue {} in setup {}", 
                    connectionId, queueName, setupId);
    }
    
    /**
     * Updates the last activity timestamp.
     */
    public void updateActivity() {
        this.lastActivityTime = clock.getAsLong();
    }
    
    /**
     * Checks if the connection is still active and healthy: it was not cleaned up, its socket is
     * open, and no write to it has failed.
     *
     * <p>Health does not depend on how long ago the server last sent data. A stream on a quiet
     * queue sends nothing, and an earlier rule that called such a connection unhealthy after
     * 5 minutes dropped every later message while the socket stayed open (finding 9).</p>
     */
    public boolean isHealthy() {
        if (!active) {
            return false;
        }

        // Check if WebSocket is still open
        if (webSocket.isClosed()) {
            logger.debug("WebSocket connection {} is closed", connectionId);
            active = false;
            return false;
        }

        return true;
    }
    
    /**
     * Sends a message to the WebSocket client.
     */
    public void sendMessage(JsonObject message) {
        if (!isHealthy()) {
            logger.warn("Message not sent: WebSocket connection {} is closed or failed", connectionId);
            return;
        }
        
        try {
            webSocket.writeTextMessage(message.encode())
                    .onFailure(writeFailure -> {
                        logger.error("Error sending message to WebSocket connection {}: {}",
                                connectionId, writeFailure.getMessage(), writeFailure);
                        active = false;
                    });
            messagesSent++;
            updateActivity();

            logger.trace("Sent message to WebSocket connection {}: {}", connectionId, message.encode());
        } catch (Exception e) {
            logger.error("Error sending message to WebSocket connection {}: {}", connectionId, e.getMessage(), e);
            active = false;
        }
    }
    
    /**
     * Sends a data message containing queue message payload.
     */
    public void sendDataMessage(Object payload, String messageId, JsonObject headers, String messageType) {
        JsonObject dataMessage = new JsonObject()
            .put("type", "data")
            .put("connectionId", connectionId)
            .put("messageId", messageId)
            .put("payload", payload)
            .put("messageType", messageType)
            .put("timestamp", System.currentTimeMillis());
        
        if (headers != null && !headers.isEmpty()) {
            dataMessage.put("headers", headers);
        }
        
        sendMessage(dataMessage);
        messagesReceived++;
    }
    
    /**
     * Sends a batch of data messages.
     */
    public void sendBatchDataMessage(JsonObject[] messages) {
        JsonObject batchMessage = new JsonObject()
            .put("type", "batch")
            .put("connectionId", connectionId)
            .put("messageCount", messages.length)
            .put("messages", messages)
            .put("timestamp", System.currentTimeMillis());
        
        sendMessage(batchMessage);
        messagesReceived += messages.length;
    }
    
    /**
     * Sends a heartbeat message to keep the connection alive.
     */
    public void sendHeartbeat() {
        JsonObject heartbeat = new JsonObject()
            .put("type", "heartbeat")
            .put("connectionId", connectionId)
            .put("timestamp", System.currentTimeMillis())
            .put("messagesReceived", messagesReceived)
            .put("messagesSent", messagesSent);
        
        sendMessage(heartbeat);
    }
    
    /**
     * Applies message filters to determine if a message should be sent.
     */
    public boolean shouldSendMessage(Object payload, JsonObject headers, String messageType) {
        if (filters == null || filters.isEmpty()) {
            return true; // No filters, send all messages
        }
        
        // Apply message type filter
        if (filters.containsKey("messageType")) {
            String requiredType = filters.getString("messageType");
            if (!requiredType.equals(messageType)) {
                return false;
            }
        }
        
        // Apply header filters
        if (filters.containsKey("headers") && headers != null) {
            JsonObject headerFilters = filters.getJsonObject("headers");
            for (String key : headerFilters.fieldNames()) {
                String requiredValue = headerFilters.getString(key);
                String actualValue = headers.getString(key);
                if (!requiredValue.equals(actualValue)) {
                    return false;
                }
            }
        }
        
        // Apply payload filters (basic string matching)
        if (filters.containsKey("payloadContains")) {
            String searchTerm = filters.getString("payloadContains");
            String payloadStr = payload.toString().toLowerCase();
            if (!payloadStr.contains(searchTerm.toLowerCase())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Cleans up resources associated with this connection.
     */
    public void cleanup() {
        logger.debug("Cleaning up WebSocket connection: {}", connectionId);
        
        active = false;
        subscribed = false;
        
        // Close the non-destructive tail observer if it exists (stops observing; consumes nothing)
        if (browser != null) {
            try {
                browser.close();
                logger.debug("Closed tail browser for connection: {}", connectionId);
            } catch (Exception e) {
                logger.warn("Error closing tail browser for connection {}: {}", connectionId, e.getMessage());
            }
            browser = null;
        }
        
        // Close WebSocket if still open
        if (!webSocket.isClosed()) {
            try {
                webSocket.close()
                        .onFailure(closeFailure -> logger.warn("Error closing WebSocket for connection {}: {}",
                                connectionId, closeFailure.getMessage()));
                logger.debug("Closed WebSocket for connection: {}", connectionId);
            } catch (Exception e) {
                logger.warn("Error closing WebSocket for connection {}: {}", connectionId, e.getMessage());
            }
        }
        
        logger.info("WebSocket connection {} cleaned up. Messages received: {}, sent: {}", 
                   connectionId, messagesReceived, messagesSent);
    }
    
    /**
     * Gets connection statistics.
     */
    public JsonObject getStatistics() {
        return new JsonObject()
            .put("connectionId", connectionId)
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("active", active)
            .put("subscribed", subscribed)
            .put("consumerGroup", consumerGroup)
            .put("batchSize", batchSize)
            .put("maxWaitTime", maxWaitTime)
            .put("messagesReceived", messagesReceived)
            .put("messagesSent", messagesSent)
            .put("createdAt", createdAt)
            .put("lastActivityTime", lastActivityTime)
            .put("uptimeMs", System.currentTimeMillis() - createdAt);
    }
    
    // Getters and setters
    
    public String getConnectionId() {
        return connectionId;
    }
    
    public ServerWebSocket getWebSocket() {
        return webSocket;
    }
    
    public String getSetupId() {
        return setupId;
    }
    
    public String getQueueName() {
        return queueName;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public boolean isSubscribed() {
        return subscribed;
    }
    
    public void setSubscribed(boolean subscribed) {
        this.subscribed = subscribed;
    }
    
    public String getConsumerGroup() {
        return consumerGroup;
    }
    
    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }
    
    public JsonObject getFilters() {
        return filters;
    }
    
    public void setFilters(JsonObject filters) {
        this.filters = filters;
    }
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, Math.min(100, batchSize)); // Clamp between 1 and 100
    }
    
    public long getMaxWaitTime() {
        return maxWaitTime;
    }
    
    public void setMaxWaitTime(long maxWaitTime) {
        this.maxWaitTime = Math.max(1000L, Math.min(60000L, maxWaitTime)); // Clamp between 1s and 60s
    }
    
    public QueueBrowser<Object> getBrowser() {
        return browser;
    }

    public void setBrowser(QueueBrowser<Object> browser) {
        this.browser = browser;
    }
    
    public long getMessagesReceived() {
        return messagesReceived;
    }
    
    public long getMessagesSent() {
        return messagesSent;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public long getLastActivityTime() {
        return lastActivityTime;
    }
}
