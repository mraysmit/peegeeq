package dev.mars.peegeeq.outbox;

import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;

/**
 * Core interface for the PostgreSQL Message Queue using Vert.x.
 * Defines operations for sending and receiving messages.
 */
public interface PgQueue<T> {
    
    /**
     * Sends a message to the queue.
     *
     * @param message The message to send
     * @return A Future that completes when the message is sent
     */
    Future<Void> send(T message);
    
    /**
     * Receives messages from the queue.
     *
     * @return A ReadStream of messages from the queue
     */
    ReadStream<T> receive();
    
    /**
     * Acknowledges that a message has been processed.
     *
     * @param messageId The ID of the message to acknowledge
     * @return A Future that completes when the message is acknowledged
     */
    Future<Void> acknowledge(String messageId);
    
    /**
     * Closes the queue connection.
     *
     * @return A Future that completes when the connection is closed
     */
    Future<Void> close();
}