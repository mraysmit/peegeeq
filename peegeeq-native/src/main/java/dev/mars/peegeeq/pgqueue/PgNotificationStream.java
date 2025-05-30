package dev.mars.peegeeq.pgqueue;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ReadStream implementation that handles PostgreSQL notifications.
 * This class is used to convert PostgreSQL notifications into a stream of messages.
 *
 * @param <T> The type of items in the stream
 */
public class PgNotificationStream<T> implements ReadStream<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNotificationStream.class);

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
        logger.debug("Created PgNotificationStream for message type: {}", messageType.getName());
    }

    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        logger.trace("Setting exception handler");
        this.exceptionHandler = handler;
        return this;
    }

    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        logger.debug("Setting data handler: {}", handler != null ? "non-null" : "null");
        this.dataHandler = handler;
        return this;
    }

    @Override
    public ReadStream<T> pause() {
        logger.debug("Pausing notification stream");
        paused = true;
        return this;
    }

    @Override
    public ReadStream<T> resume() {
        logger.debug("Resuming notification stream");
        paused = false;
        return this;
    }

    @Override
    public ReadStream<T> endHandler(Handler<Void> handler) {
        logger.trace("Setting end handler");
        this.endHandler = handler;
        return this;
    }

    @Override
    public ReadStream<T> fetch(long amount) {
        // No-op for this implementation
        logger.trace("Fetch called with amount: {} (no-op)", amount);
        return this;
    }

    /**
     * Handles a notification from PostgreSQL.
     *
     * @param message The message from the notification
     */
    public void handleNotification(T message) {
        logger.debug("Handling notification: {}", message);
        if (paused) {
            logger.debug("Stream is paused, not delivering notification");
            return;
        }
        if (dataHandler == null) {
            logger.debug("No data handler registered, notification will be ignored");
            return;
        }
        logger.trace("Delivering notification to data handler");
        vertx.runOnContext(v -> {
            logger.trace("Notification delivered to data handler");
            dataHandler.handle(message);
        });
    }

    /**
     * Handles an error from PostgreSQL.
     *
     * @param error The error
     */
    public void handleError(Throwable error) {
        logger.error("Handling error: {}", error.getMessage(), error);
        if (exceptionHandler == null) {
            logger.warn("No exception handler registered, error will be ignored: {}", error.getMessage());
            return;
        }
        logger.debug("Delivering error to exception handler");
        vertx.runOnContext(v -> {
            logger.trace("Error delivered to exception handler");
            exceptionHandler.handle(error);
        });
    }

    /**
     * Handles the end of the stream.
     */
    public void handleEnd() {
        logger.info("Handling end of stream");
        if (endHandler == null) {
            logger.debug("No end handler registered, end signal will be ignored");
            return;
        }
        logger.debug("Delivering end signal to end handler");
        vertx.runOnContext(v -> {
            logger.trace("End signal delivered to end handler");
            endHandler.handle(null);
        });
    }
}
