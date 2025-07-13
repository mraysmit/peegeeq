package dev.mars.peegeeq.pgqueue;

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


import dev.mars.peegeeq.api.Message;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the Message interface for pgqueue PostgreSQL queue using Vert.x.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Implementation of the Message interface for pgqueue PostgreSQL queue using Vert.x.
 */
public class PgNativeMessage<T> implements Message<T> {
    
    private final String id;
    private final T payload;
    private final Instant createdAt;
    private final Map<String, String> headers;
    
    /**
     * Creates a new PgNativeMessage with the given parameters.
     *
     * @param id The unique identifier of the message
     * @param payload The payload of the message
     * @param createdAt The timestamp when the message was created
     * @param headers The headers associated with the message
     */
    public PgNativeMessage(String id, T payload, Instant createdAt, Map<String, String> headers) {
        this.id = Objects.requireNonNull(id, "Message ID cannot be null");
        this.payload = payload;
        this.createdAt = Objects.requireNonNull(createdAt, "Created timestamp cannot be null");
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
    }
    
    /**
     * Creates a new PgNativeMessage with the given ID and payload, using the current time as the creation timestamp.
     *
     * @param id The unique identifier of the message
     * @param payload The payload of the message
     */
    public PgNativeMessage(String id, T payload) {
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
    
    @Override
    public String toString() {
        return "PgNativeMessage{" +
                "id='" + id + '\'' +
                ", payload=" + payload +
                ", createdAt=" + createdAt +
                ", headers=" + headers +
                '}';
    }
}