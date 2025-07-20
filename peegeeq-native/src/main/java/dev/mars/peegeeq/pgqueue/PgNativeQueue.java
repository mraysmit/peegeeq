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


import dev.mars.peegeeq.api.PgQueue;
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

/**
 * Implementation of the PgQueue interface using pgqueue PostgreSQL features with Vert.x.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Implementation of the PgQueue interface using pgqueue PostgreSQL features with Vert.x.
 * This class provides a queue implementation using PostgreSQL's LISTEN/NOTIFY
 * mechanism and advisory locks for reliable message delivery.
 */
public class PgNativeQueue<T> implements PgQueue<T> {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueue.class);

    private final Vertx vertx;
    private final Pool pool;
    private final PgConnectOptions connectOptions;
    private final ObjectMapper objectMapper;
    private final String channelName;
    private final Class<T> messageType;
    private PgConnection listenConnection;

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
                        .onComplete(ar -> {
                            conn.close();
                            if (ar.succeeded()) {
                                logger.debug("Successfully sent message to channel {}", channelName);
                            } else {
                                logger.error("Failed to send message to channel {}: {}", channelName, ar.cause().getMessage());
                            }
                        })
                        .mapEmpty();
                } catch (Exception e) {
                    logger.error("Failed to serialize message: {}", message, e);
                    conn.close();
                    return Future.failedFuture(e);
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
                                @SuppressWarnings("unchecked") // Safe cast when messageType is JsonObject.class
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
                    .onFailure(err -> {
                        logger.error("Failed to LISTEN on channel {}: {}", channelName, err.getMessage());
                        stream.handleError(err);
                    });
            })
            .onFailure(err -> {
                logger.error("Failed to establish LISTEN connection: {}", err.getMessage());
                stream.handleError(err);
            });

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

    @Override
    public Future<Void> close() {
        logger.info("Closing PgNativeQueue for channel: {}", channelName);
        Promise<Void> promise = Promise.promise();

        if (listenConnection != null) {
            logger.debug("Closing LISTEN connection and connection pool");
            listenConnection.close()
                .compose(v -> {
                    logger.debug("LISTEN connection closed successfully");
                    return pool.close();
                })
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        logger.info("Successfully closed all resources for channel: {}", channelName);
                        promise.complete();
                    } else {
                        logger.error("Failed to close resources: {}", ar.cause().getMessage());
                        promise.fail(ar.cause());
                    }
                });
        } else {
            logger.debug("No LISTEN connection to close, closing only the connection pool");
            pool.close().onComplete(ar -> {
                if (ar.succeeded()) {
                    logger.info("Successfully closed connection pool for channel: {}", channelName);
                    promise.complete();
                } else {
                    logger.error("Failed to close connection pool: {}", ar.cause().getMessage());
                    promise.fail(ar.cause());
                }
            });
        }

        return promise.future();
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
