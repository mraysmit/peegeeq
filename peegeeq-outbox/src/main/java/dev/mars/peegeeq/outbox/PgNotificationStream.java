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


import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A ReadStream implementation that handles PostgreSQL notifications.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
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