package dev.mars.peegeeq.api;

/**
 * Interface for consuming messages from a queue.
 * 
 * @param <T> The type of message payload
 */
public interface MessageConsumer<T> extends AutoCloseable {
    
    /**
     * Subscribes to messages with the given handler.
     * 
     * @param handler The message handler to process received messages
     */
    void subscribe(MessageHandler<T> handler);
    
    /**
     * Unsubscribes from message processing.
     */
    void unsubscribe();
    
    /**
     * Closes the consumer and releases any resources.
     */
    @Override
    void close();
}
