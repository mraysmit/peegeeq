package dev.mars.peegeeq.vertx;

import dev.mars.peegeeq.api.Message;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgNotification;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Implementation of the PgQueue interface using native PostgreSQL features with Vert.x.
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
    }

    @Override
    public Future<Void> send(T message) {
        // In a real implementation, this would serialize the message and use NOTIFY to send it
        return pool.getConnection()
            .compose(conn -> {
                return conn.query("NOTIFY " + channelName + ", '" + message + "'")
                    .execute()
                    .onComplete(ar -> conn.close())
                    .mapEmpty();
            });
    }

    @Override
    public ReadStream<T> receive() {
        // Create a custom ReadStream implementation that listens for notifications
        PgNotificationStream<T> stream = new PgNotificationStream<>(vertx, messageType, objectMapper);

        // Set up the LISTEN connection
        PgConnection.connect(vertx, connectOptions)
            .onSuccess(conn -> {
                this.listenConnection = conn;
                conn.notificationHandler(notification -> {
                    if (channelName.equals(notification.getChannel())) {
                        try {
                            // Parse the notification payload and push to the stream
                            T message = objectMapper.readValue(notification.getPayload(), messageType);
                            stream.handleNotification(message);
                        } catch (Exception e) {
                            stream.handleError(e);
                        }
                    }
                });

                // Start listening on the channel
                conn.query("LISTEN " + channelName).execute()
                    .onFailure(err -> stream.handleError(err));
            })
            .onFailure(err -> stream.handleError(err));

        return stream;
    }

    @Override
    public Future<Void> acknowledge(String messageId) {
        // In a real implementation, this would release any advisory locks
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            // Placeholder for actual implementation
            logger.debug("Releasing advisory lock for message: {}", messageId);
            promise.complete();
        });
        return promise.future();
    }

    @Override
    public Future<Void> close() {
        // Close the listen connection and the pool
        Promise<Void> promise = Promise.promise();

        if (listenConnection != null) {
            listenConnection.close()
                .compose(v -> pool.close())
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        promise.complete();
                    } else {
                        promise.fail(ar.cause());
                    }
                });
        } else {
            pool.close().onComplete(ar -> {
                if (ar.succeeded()) {
                    promise.complete();
                } else {
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
        return new PgNativeMessage<>(UUID.randomUUID().toString(), payload);
    }
}
