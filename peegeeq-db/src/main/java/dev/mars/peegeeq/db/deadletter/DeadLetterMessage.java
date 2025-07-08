package dev.mars.peegeeq.db.deadletter;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message in the dead letter queue.
 */
public class DeadLetterMessage {
    private final long id;
    private final String originalTable;
    private final long originalId;
    private final String topic;
    private final String payload;
    private final Instant originalCreatedAt;
    private final Instant failedAt;
    private final String failureReason;
    private final int retryCount;
    private final Map<String, String> headers;
    private final String correlationId;
    private final String messageGroup;
    
    public DeadLetterMessage(long id, String originalTable, long originalId, String topic, 
                           String payload, Instant originalCreatedAt, Instant failedAt, 
                           String failureReason, int retryCount, Map<String, String> headers,
                           String correlationId, String messageGroup) {
        this.id = id;
        this.originalTable = Objects.requireNonNull(originalTable, "Original table cannot be null");
        this.originalId = originalId;
        this.topic = Objects.requireNonNull(topic, "Topic cannot be null");
        this.payload = Objects.requireNonNull(payload, "Payload cannot be null");
        this.originalCreatedAt = Objects.requireNonNull(originalCreatedAt, "Original created at cannot be null");
        this.failedAt = Objects.requireNonNull(failedAt, "Failed at cannot be null");
        this.failureReason = Objects.requireNonNull(failureReason, "Failure reason cannot be null");
        this.retryCount = retryCount;
        this.headers = headers;
        this.correlationId = correlationId;
        this.messageGroup = messageGroup;
    }
    
    public long getId() {
        return id;
    }
    
    public String getOriginalTable() {
        return originalTable;
    }
    
    public long getOriginalId() {
        return originalId;
    }
    
    public String getTopic() {
        return topic;
    }
    
    public String getPayload() {
        return payload;
    }
    
    public Instant getOriginalCreatedAt() {
        return originalCreatedAt;
    }
    
    public Instant getFailedAt() {
        return failedAt;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public String getMessageGroup() {
        return messageGroup;
    }
    
    @Override
    public String toString() {
        return "DeadLetterMessage{" +
                "id=" + id +
                ", originalTable='" + originalTable + '\'' +
                ", originalId=" + originalId +
                ", topic='" + topic + '\'' +
                ", originalCreatedAt=" + originalCreatedAt +
                ", failedAt=" + failedAt +
                ", failureReason='" + failureReason + '\'' +
                ", retryCount=" + retryCount +
                ", correlationId='" + correlationId + '\'' +
                ", messageGroup='" + messageGroup + '\'' +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeadLetterMessage that = (DeadLetterMessage) o;
        return id == that.id &&
               originalId == that.originalId &&
               retryCount == that.retryCount &&
               Objects.equals(originalTable, that.originalTable) &&
               Objects.equals(topic, that.topic) &&
               Objects.equals(payload, that.payload) &&
               Objects.equals(originalCreatedAt, that.originalCreatedAt) &&
               Objects.equals(failedAt, that.failedAt) &&
               Objects.equals(failureReason, that.failureReason) &&
               Objects.equals(headers, that.headers) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(messageGroup, that.messageGroup);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, originalTable, originalId, topic, payload, originalCreatedAt, 
                          failedAt, failureReason, retryCount, headers, correlationId, messageGroup);
    }
}
