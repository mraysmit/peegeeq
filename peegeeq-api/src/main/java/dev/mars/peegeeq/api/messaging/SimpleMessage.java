package dev.mars.peegeeq.api.messaging;

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

// No imports needed - all classes are in the same package now
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Simple implementation of the Message interface.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }
    
    public SimpleMessage(String id, String topic, T payload, Map<String, String> headers, 
                        String correlationId, String messageGroup) {
        this(id, topic, payload, headers, correlationId, messageGroup, Instant.now());
    }
    
    public SimpleMessage(String id, String topic, T payload, Map<String, String> headers) {
        this(id, topic, payload, headers, null, null, Instant.now());
    }
    
    public SimpleMessage(String id, String topic, T payload) {
        this(id, topic, payload, null, null, null, Instant.now());
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
     * @return The topic name
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
    
    @Override
    public String toString() {
        return String.format(
            "SimpleMessage{id='%s', topic='%s', payload=%s, headers=%s, correlationId='%s', messageGroup='%s', createdAt=%s}",
            id, topic, payload, headers, correlationId, messageGroup, createdAt
        );
    }
}
