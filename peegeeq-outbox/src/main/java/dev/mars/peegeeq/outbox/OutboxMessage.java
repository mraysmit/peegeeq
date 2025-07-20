package dev.mars.peegeeq.outbox;

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


import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the Message interface for the Outbox pattern using Vert.x.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Implementation of the Message interface for the Outbox pattern using Vert.x.
 */
public class OutboxMessage<T> implements dev.mars.peegeeq.api.messaging.Message<T> {

    private final String id;
    private final T payload;
    private final Instant createdAt;
    private final Map<String, String> headers;
    private final String correlationId;
    
    /**
     * Creates a new OutboxMessage with the given parameters.
     *
     * @param id The unique identifier of the message
     * @param payload The payload of the message
     * @param createdAt The timestamp when the message was created
     * @param headers The headers associated with the message
     */
    public OutboxMessage(String id, T payload, Instant createdAt, Map<String, String> headers) {
        this(id, payload, createdAt, headers, null);
    }

    /**
     * Creates a new OutboxMessage with the given parameters including correlation ID.
     *
     * @param id The unique identifier of the message
     * @param payload The payload of the message
     * @param createdAt The timestamp when the message was created
     * @param headers The headers associated with the message
     * @param correlationId The correlation ID for the message
     */
    public OutboxMessage(String id, T payload, Instant createdAt, Map<String, String> headers, String correlationId) {
        this.id = Objects.requireNonNull(id, "Message ID cannot be null");
        this.payload = payload;
        this.createdAt = Objects.requireNonNull(createdAt, "Created timestamp cannot be null");
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.correlationId = correlationId;
    }
    
    /**
     * Creates a new OutboxMessage with the given ID and payload, using the current time as the creation timestamp.
     *
     * @param id The unique identifier of the message
     * @param payload The payload of the message
     */
    public OutboxMessage(String id, T payload) {
        this(id, payload, Instant.now(), null);
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
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Gets the correlation ID for this message.
     *
     * @return The correlation ID, or null if not set
     */
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public String toString() {
        return "OutboxMessage{" +
                "id='" + id + '\'' +
                ", payload=" + payload +
                ", createdAt=" + createdAt +
                ", correlationId='" + correlationId + '\'' +
                ", headers=" + headers +
                '}';
    }
}