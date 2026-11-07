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


import dev.mars.peegeeq.api.ReactiveQueue;
import dev.mars.peegeeq.api.messaging.Message;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of the ReactiveQueue interface using native PostgreSQL features with Vert.x.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * This class provides a queue implementation using PostgreSQL's LISTEN/NOTIFY
 * mechanism and advisory locks for reliable message delivery.
 *
 * @param <T> The type of message payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.1 - Updated to implement ReactiveQueue (renamed from PgQueue)
 */
public class PgNativeQueue<T> implements ReactiveQueue<T> {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueue.class);

    private final Vertx vertx;
    private final Pool pool;
    private final PgConnectOptions connectOptions;
    private final ObjectMapper objectMapper;
    private final String channelName;
    private final Class<T> messageType;
    private PgConnection listenConnection;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new PgNativeQueue with the given parameters.
     *
     * @param vertx The Vertx instance
     * @param connectOptions The PostgreSQL connection options
     * @param poolOptions The pool options
     * @param objectMapper The object mapper for serializing and deserializing messages
     * @param channelName The name of the LISTEN/NOTIFY channel to use
     * @param messageType The class of the message payload
     */
    public PgNativeQueue(Vertx vertx, PgConnectOptions connectOptions, PoolOptions poolOptions,
                       ObjectMapper objectMapper, String channelName, Class<T> messageType) {
        this.vertx = vertx;
        this.connectOptions = connectOptions;
        this.pool = Pool.pool(vertx, connectOptions, poolOptions);
        this.objectMapper = objectMapper;
        this.channelName = channelName;
        this.messageType = messageType;
        logger.info("Initialized PgNativeQueue with channel: {}, messageType: {}", channelName, messageType.getName());
    }

    @Override
    public Future<Void> send(T message) {
        logger.debug("Sending message to channel {}: {}", channelName, message);
        return pool.getConnection()
            .compose(conn -> {
                logger.trace("Got connection for sending message");
                try {
                    // Properly serialize the message to JSON
                    String jsonPayload;
                    if (message instanceof JsonObject) {
                        // Use Vert.x's JsonObject for JsonObject type
                        jsonPayload = ((JsonObject) message).encode();
                        logger.trace("Serialized JsonObject message");
                    } else {
                        // Use Jackson for other types
                        jsonPayload = objectMapper.writeValueAsString(message);
                        logger.trace("Serialized {} message with Jackson", messageType.getSimpleName());
                    }
                    // Escape single quotes for SQL
                    jsonPayload = jsonPayload.replace("'", "''");
                    return conn.query("NOTIFY " + channelName + ", '" + jsonPayload + "'")
                        .execute()
                        .onSuccess(rows -> logger.debug("Successfully sent message to channel {}", channelName))
                        .onFailure(error -> logger.error("Failed to send message to channel {}: {}",
                                channelName, error.getMessage()))
                        .<Void>mapEmpty()
                        .eventually(() -> conn.close()
                                .onFailure(closeError -> logger.warn("Failed to return the send connection of channel {}: {}",
                                        channelName, closeError.getMessage())));
                } catch (Exception e) {
                    logger.error("Failed to serialize message: {}", message, e);
                    return conn.close()
                        .onFailure(closeError -> logger.warn("Failed to return the send connection of channel {}: {}",
                                channelName, closeError.getMessage()))
                        .transform(closed -> Future.<Void>failedFuture(e));
                }
            });
    }

    @Override
    public ReadStream<T> receive() {
        logger.debug("Setting up receive stream for channel: {}", channelName);

        // Create a custom ReadStream implementation that listens for notifications
        PgNotificationStream<T> stream = new PgNotificationStream<>(vertx, messageType, objectMapper);

        // Set up the LISTEN connection
        logger.trace("Connecting to PostgreSQL for LISTEN/NOTIFY");
        PgConnection.connect(vertx, connectOptions)
            .onSuccess(conn -> {
                if (closed.get()) {
                    // The queue was closed while the connection was being made. Close it now:
                    // close() could not see it, and it would otherwise stay open.
                    logger.debug("LISTEN connection for channel {} arrived after close; closing it", channelName);
                    conn.close().onFailure(closeError -> logger.warn(
                            "Failed to close the late LISTEN connection of channel {}: {}",
                            channelName, closeError.getMessage()));
                    stream.handleEnd();
                    return;
                }
                this.listenConnection = conn;
                logger.debug("Successfully established LISTEN connection to PostgreSQL");

                conn.notificationHandler(notification -> {
                    if (channelName.equals(notification.getChannel())) {
                        logger.debug("Received notification on channel {}: {}", notification.getChannel(), notification.getPayload());
                        try {
                            // Parse the notification payload and push to the stream
                            T message;
                            if (messageType == JsonObject.class) {
                                // Use Vert.x's JsonObject for JsonObject type
                                T castedMessage = (T) new JsonObject(notification.getPayload());
                                message = castedMessage;
                                logger.trace("Deserialized notification payload to JsonObject");
                            } else {
                                // Use Jackson for other types
                                message = objectMapper.readValue(notification.getPayload(), messageType);
                                logger.trace("Deserialized notification payload to {}", messageType.getSimpleName());
                            }
                            stream.handleNotification(message);
                            logger.debug("Successfully processed notification and passed to stream handler");
                        } catch (Exception e) {
                            logger.error("Failed to process notification: {}", notification.getPayload(), e);
                            stream.handleError(e);
                        }
                    }
                });

                // Start listening on the channel
                logger.debug("Executing LISTEN command for channel: {}", channelName);
                conn.query("LISTEN " + channelName).execute()
                    .onSuccess(v -> logger.info("Now listening on channel: {}", channelName))
                    .onFailure(err -> failOrEndStream(stream, "Failed to LISTEN on channel " + channelName, err));
            })
            .onFailure(err -> failOrEndStream(stream, "Failed to establish LISTEN connection for channel " + channelName, err));

        return stream;
    }

    @Override
    public Future<Void> acknowledge(String messageId) {
        logger.debug("Acknowledging message: {}", messageId);
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            // Placeholder for actual implementation
            logger.debug("Releasing advisory lock for message: {}", messageId);
            promise.complete();
            logger.trace("Advisory lock released for message: {}", messageId);
        });
        return promise.future();
    }

    /**
     * Reports a LISTEN failure to the stream. After {@link #close()} a failure is part of the
     * close: the stream ends and nothing is logged above DEBUG. Before it, the failure is an
     * error. The queue's own closed state decides, not the text of the exception.
     */
    private void failOrEndStream(PgNotificationStream<T> stream, String context, Throwable err) {
        if (closed.get()) {
            logger.debug("{} after the queue was closed: {}", context, err.getMessage());
            stream.handleEnd();
        } else {
            logger.error("{}: {}", context, err.getMessage());
            stream.handleError(err);
        }
    }

    @Override
    public Future<Void> close() {
        closed.set(true);
        logger.info("Closing PgNativeQueue for channel: {}", channelName);

        PgConnection connection = listenConnection;
        Future<Void> listenClose = connection != null ? connection.close() : Future.succeededFuture();
        return listenClose
            .compose(v -> pool.close())
            .onSuccess(v -> logger.info("Successfully closed all resources for channel: {}", channelName))
            .onFailure(error -> logger.error("Failed to close resources for channel {}: {}",
                    channelName, error.getMessage(), error));
    }

    /**
     * Creates a new message with a random ID.
     *
     * @param payload The payload of the message
     * @return A new message
     */
    public Message<T> createMessage(T payload) {
        String messageId = UUID.randomUUID().toString();
        logger.debug("Creating new message with ID: {} and payload type: {}", messageId, 
                payload != null ? payload.getClass().getSimpleName() : "null");
        Message<T> message = new PgNativeMessage<>(messageId, payload);
        logger.trace("Created message: {}", message);
        return message;
    }
}
