package dev.mars.peegeeq.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for producing messages to a queue.
 * 
 * @param <T> The type of message payload
 */
public interface MessageProducer<T> extends AutoCloseable {
    
    /**
     * Sends a message with the given payload.
     * 
     * @param payload The message payload
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload);
    
    /**
     * Sends a message with the given payload and headers.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers);
    
    /**
     * Sends a message with the given payload, headers, and correlation ID.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @param correlationId The correlation ID for message tracking
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId);
    
    /**
     * Sends a message with the given payload, headers, correlation ID, and message group.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @param correlationId The correlation ID for message tracking
     * @param messageGroup The message group for ordering
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup);
    
    /**
     * Closes the producer and releases any resources.
     */
    @Override
    void close();
}
