package dev.mars.peegeeq.pgqueue;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An empty implementation of ReadStream that doesn't emit any items.
 * Used as a placeholder when no actual stream is available.
 *
 * @param <T> The type of items in the stream
 */
public class EmptyReadStream<T> implements ReadStream<T> {
    private static final Logger logger = LoggerFactory.getLogger(EmptyReadStream.class);

    public EmptyReadStream() {
        logger.debug("Created EmptyReadStream");
    }

    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        logger.trace("Setting exception handler: {} (no-op)", handler != null ? "non-null" : "null");
        return this;
    }

    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        logger.trace("Setting data handler: {} (no-op)", handler != null ? "non-null" : "null");
        return this;
    }

    @Override
    public ReadStream<T> pause() {
        logger.trace("Pausing stream (no-op)");
        return this;
    }

    @Override
    public ReadStream<T> resume() {
        logger.trace("Resuming stream (no-op)");
        return this;
    }

    @Override
    public ReadStream<T> endHandler(Handler<Void> endHandler) {
        logger.trace("Setting end handler: {}", endHandler != null ? "non-null" : "null");
        if (endHandler != null) {
            logger.debug("Immediately calling end handler since this is an empty stream");
            endHandler.handle(null);
        }
        return this;
    }

    @Override
    public ReadStream<T> fetch(long amount) {
        logger.trace("Fetch called with amount: {} (no-op)", amount);
        return this;
    }
}
