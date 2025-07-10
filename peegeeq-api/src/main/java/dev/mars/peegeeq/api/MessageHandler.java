package dev.mars.peegeeq.api;

import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for handling messages.
 * 
 * @param <T> The type of message payload
 */
@FunctionalInterface
public interface MessageHandler<T> {
    
    /**
     * Handles a received message.
     * 
     * @param message The message to handle
     * @return A CompletableFuture that completes when the message is processed
     */
    CompletableFuture<Void> handle(Message<T> message);
}
