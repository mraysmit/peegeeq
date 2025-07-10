package dev.mars.peegeeq.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Simple implementation of the Message interface.
 * 
 * @param <T> The type of message payload
 */
public class SimpleMessage<T> implements Message<T> {
    
    private final String id;
    private final String topic;
    private final T payload;
    private final Map<String, String> headers;
    private final String correlationId;
    private final String messageGroup;
    private final Instant createdAt;
    
    public SimpleMessage(String id, String topic, T payload, Map<String, String> headers, 
                        String correlationId, String messageGroup, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Message ID cannot be null");
        this.topic = Objects.requireNonNull(topic, "Topic cannot be null");
        this.payload = Objects.requireNonNull(payload, "Payload cannot be null");
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.correlationId = correlationId;
        this.messageGroup = messageGroup;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at cannot be null");
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public T getPayload() {
        return payload;
    }
    
    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    /**
     * Gets the topic this message belongs to.
     * 
     * @return The message topic
     */
    public String getTopic() {
        return topic;
    }
    
    /**
     * Gets the correlation ID for this message.
     * 
     * @return The correlation ID, or null if not set
     */
    public String getCorrelationId() {
        return correlationId;
    }
    
    /**
     * Gets the message group for ordering.
     * 
     * @return The message group, or null if not set
     */
    public String getMessageGroup() {
        return messageGroup;
    }
    
    @Override
    public String toString() {
        return "SimpleMessage{" +
                "id='" + id + '\'' +
                ", topic='" + topic + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", messageGroup='" + messageGroup + '\'' +
                ", createdAt=" + createdAt +
                ", headers=" + headers +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SimpleMessage<?> that = (SimpleMessage<?>) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(topic, that.topic) &&
               Objects.equals(payload, that.payload) &&
               Objects.equals(headers, that.headers) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(messageGroup, that.messageGroup) &&
               Objects.equals(createdAt, that.createdAt);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, topic, payload, headers, correlationId, messageGroup, createdAt);
    }
}
