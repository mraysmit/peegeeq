package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an active Server-Sent Events (SSE) connection for real-time message streaming.
 * 
 * Manages the state and configuration of a single SSE connection,
 * including subscription settings, filters, and the associated message consumer.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class SSEConnection {
    
    private static final Logger logger = LoggerFactory.getLogger(SSEConnection.class);
    
    private final String connectionId;
    private final HttpServerResponse response;
    private final String setupId;
    private final String queueName;
    private final long createdAt;
    
    // Connection state
    private volatile boolean active = true;

    // Reconnection support
    private String resumeFromMessageId; // Last-Event-ID for SSE reconnection
    private volatile boolean resumePointReached = false;

    // Subscription configuration
    private String consumerGroup;
    private JsonObject filters;
    private int batchSize = 1;
    private long maxWaitTime = 5000L; // 5 seconds default

    // Batching support
    private final List<JsonObject> batchBuffer = new ArrayList<>();
    private Long batchTimerId; // Timer ID for batch timeout
    private String lastMessageIdInBatch; // Track last message ID for batch event

    // Message consumer
    private MessageConsumer<Object> consumer;
    
    // Statistics
    private volatile long messagesReceived = 0;
    private volatile long messagesSent = 0;
    private volatile long lastActivityTime;
    
    public SSEConnection(String connectionId, HttpServerResponse response, String setupId, String queueName) {
        this.connectionId = connectionId;
        this.response = response;
        this.setupId = setupId;
        this.queueName = queueName;
        this.createdAt = System.currentTimeMillis();
        this.lastActivityTime = this.createdAt;
        
        logger.debug("Created SSE connection: {} for queue {} in setup {}", 
                    connectionId, queueName, setupId);
    }
    
    /**
     * Updates the last activity timestamp.
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Checks if the connection is still active and healthy.
     */
    public boolean isHealthy() {
        if (!active) {
            return false;
        }
        
        // Check if HTTP response is still open
        if (response.closed()) {
            logger.debug("SSE connection {} is closed", connectionId);
            active = false;
            return false;
        }
        
        // Check for timeout (no activity for more than 5 minutes)
        long inactiveTime = System.currentTimeMillis() - lastActivityTime;
        if (inactiveTime > 300000) { // 5 minutes
            logger.warn("SSE connection {} has been inactive for {} ms", connectionId, inactiveTime);
            return false;
        }
        
        return true;
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
     * Increments the messages received counter.
     */
    public void incrementMessagesReceived() {
        messagesReceived++;
    }
    
    /**
     * Adds to the messages received counter.
     */
    public void addMessagesReceived(long count) {
        messagesReceived += count;
    }
    
    /**
     * Increments the messages sent counter.
     */
    public void incrementMessagesSent() {
        messagesSent++;
    }
    
    /**
     * Cleans up resources associated with this connection.
     */
    public void cleanup() {
        logger.debug("Cleaning up SSE connection: {}", connectionId);
        
        active = false;
        
        // Close consumer if it exists
        if (consumer != null) {
            try {
                consumer.close();
                logger.debug("Closed message consumer for SSE connection: {}", connectionId);
            } catch (Exception e) {
                logger.warn("Error closing message consumer for SSE connection {}: {}", connectionId, e.getMessage());
            }
            consumer = null;
        }
        
        // Close HTTP response if still open
        if (!response.closed()) {
            try {
                response.end();
                logger.debug("Closed HTTP response for SSE connection: {}", connectionId);
            } catch (Exception e) {
                logger.warn("Error closing HTTP response for SSE connection {}: {}", connectionId, e.getMessage());
            }
        }
        
        logger.info("SSE connection {} cleaned up. Messages received: {}, sent: {}", 
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
    
    public HttpServerResponse getResponse() {
        return response;
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
    
    public MessageConsumer<Object> getConsumer() {
        return consumer;
    }
    
    public void setConsumer(MessageConsumer<Object> consumer) {
        this.consumer = consumer;
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

    public String getResumeFromMessageId() {
        return resumeFromMessageId;
    }

    public void setResumeFromMessageId(String resumeFromMessageId) {
        this.resumeFromMessageId = resumeFromMessageId;
    }

    public boolean isResumePointReached() {
        return resumePointReached;
    }

    public void setResumePointReached(boolean resumePointReached) {
        this.resumePointReached = resumePointReached;
    }

    public List<JsonObject> getBatchBuffer() {
        return batchBuffer;
    }

    public Long getBatchTimerId() {
        return batchTimerId;
    }

    public void setBatchTimerId(Long batchTimerId) {
        this.batchTimerId = batchTimerId;
    }

    public String getLastMessageIdInBatch() {
        return lastMessageIdInBatch;
    }

    public void setLastMessageIdInBatch(String lastMessageIdInBatch) {
        this.lastMessageIdInBatch = lastMessageIdInBatch;
    }
}
