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


import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An empty implementation of ReadStream that doesn't emit any items.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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
