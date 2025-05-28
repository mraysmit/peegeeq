package dev.mars.peegeeq.api;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Core interface for the PostgreSQL Message Queue.
 * Defines operations for sending and receiving messages.
 */
public interface PgQueue<T> {
    
    /**
     * Sends a message to the queue.
     *
     * @param message The message to send
     * @return A Mono that completes when the message is sent
     */
    Mono<Void> send(T message);
    
    /**
     * Receives messages from the queue.
     *
     * @return A Flux of messages from the queue
     */
    Flux<T> receive();
    
    /**
     * Acknowledges that a message has been processed.
     *
     * @param messageId The ID of the message to acknowledge
     * @return A Mono that completes when the message is acknowledged
     */
    Mono<Void> acknowledge(String messageId);
    
    /**
     * Closes the queue connection.
     *
     * @return A Mono that completes when the connection is closed
     */
    Mono<Void> close();
}