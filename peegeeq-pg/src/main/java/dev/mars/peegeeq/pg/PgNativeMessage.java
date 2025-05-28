package dev.mars.peegeeq.pg;

import dev.mars.peegeeq.api.Message;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the Message interface for native PostgreSQL queue.
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