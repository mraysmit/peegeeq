package dev.mars.peegeeq.api;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a message in the PostgreSQL Message Queue.
 */
public interface Message<T> {
    
    /**
     * Gets the unique identifier of the message.
     *
     * @return The message ID
     */
    String getId();
    
    /**
     * Gets the payload of the message.
     *
     * @return The message payload
     */
    T getPayload();
    
    /**
     * Gets the timestamp when the message was created.
     *
     * @return The creation timestamp
     */
    Instant getCreatedAt();
    
    /**
     * Gets the headers associated with the message.
     *
     * @return The message headers
     */
    Map<String, String> getHeaders();
}