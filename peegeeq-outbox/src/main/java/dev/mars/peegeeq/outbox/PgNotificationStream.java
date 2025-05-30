package dev.mars.peegeeq.outbox;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A ReadStream implementation that handles PostgreSQL notifications.
 * This class is used to convert PostgreSQL notifications into a stream of messages.
 *
 * @param <T> The type of items in the stream
 */
public class PgNotificationStream<T> implements ReadStream<T> {
    private final Vertx vertx;
    private final Class<T> messageType;
    private final ObjectMapper objectMapper;
    
    private Handler<T> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private boolean paused = false;
    
    /**
     * Creates a new PgNotificationStream.
     *
     * @param vertx The Vertx instance
     * @param messageType The class of the message payload
     * @param objectMapper The object mapper for deserializing messages
     */
    public PgNotificationStream(Vertx vertx, Class<T> messageType, ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.messageType = messageType;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        this.dataHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> pause() {
        paused = true;
        return this;
    }
    
    @Override
    public ReadStream<T> resume() {
        paused = false;
        return this;
    }
    
    @Override
    public ReadStream<T> endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> fetch(long amount) {
        // No-op for this implementation
        return this;
    }
    
    /**
     * Handles a notification from PostgreSQL.
     *
     * @param message The message from the notification
     */
    public void handleNotification(T message) {
        if (!paused && dataHandler != null) {
            vertx.runOnContext(v -> dataHandler.handle(message));
        }
    }
    
    /**
     * Handles an error from PostgreSQL.
     *
     * @param error The error
     */
    public void handleError(Throwable error) {
        if (exceptionHandler != null) {
            vertx.runOnContext(v -> exceptionHandler.handle(error));
        }
    }
    
    /**
     * Handles the end of the stream.
     */
    public void handleEnd() {
        if (endHandler != null) {
            vertx.runOnContext(v -> endHandler.handle(null));
        }
    }
}