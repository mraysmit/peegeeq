package dev.mars.peegeeq.vertx;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

/**
 * An empty implementation of ReadStream that doesn't emit any items.
 * Used as a placeholder when no actual stream is available.
 *
 * @param <T> The type of items in the stream
 */
public class EmptyReadStream<T> implements ReadStream<T> {

    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        return this;
    }

    @Override
    public ReadStream<T> pause() {
        return this;
    }

    @Override
    public ReadStream<T> resume() {
        return this;
    }

    @Override
    public ReadStream<T> endHandler(Handler<Void> endHandler) {
        if (endHandler != null) {
            endHandler.handle(null);
        }
        return this;
    }

    @Override
    public ReadStream<T> fetch(long amount) {
        return this;
    }
}
