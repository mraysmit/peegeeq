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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of QueueBrowser for native PostgreSQL queues.
 * Allows browsing messages without consuming them.
 *
 * <p>{@link #browse(int, int)} is the point-in-time non-destructive read; {@link #tail(MessageHandler)}
 * is the live non-destructive observe, delegated to a {@link PgNativeQueueObserver}. Neither path
 * acks, locks, or removes messages.
 *
 * @param <T> The type of message payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgNativeQueueBrowser<T> implements QueueBrowser<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueBrowser.class);

    private final String topic;
    private final Class<T> payloadType;
    private final Pool pool;
    private final ObjectMapper objectMapper;
    private final String schema;
    // Adapter is used only by tail() to give the observer a dedicated (non-pooled) LISTEN connection.
    private final VertxPoolAdapter poolAdapter;
    private volatile boolean closed = false;
    // The live tail observer (one per browser); null until tail() is called, stopped in close().
    private volatile PgNativeQueueObserver<T> observer;

    // The schema-defaulting constructor was removed deliberately: the browser's SQL is
    // schema-qualified, and PeeGeeQ has no default schema — callers pass it explicitly.

    public PgNativeQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper,
            String schema, VertxPoolAdapter poolAdapter) {
        this.poolAdapter = poolAdapter;
        this.topic = topic;
        this.payloadType = payloadType;
        this.pool = pool;
        this.objectMapper = objectMapper;
        this.schema = Objects.requireNonNull(schema,
            "schema cannot be null — PeeGeeQ has no default schema");
    }

    @Override
    public Future<List<Message<T>>> browse(int limit, int offset) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Browser is closed"));
        }

        String sql = String.format("""
                SELECT id, payload, headers, created_at, status
                FROM %s.queue_messages
                WHERE topic = $1
                ORDER BY id DESC
                LIMIT $2 OFFSET $3
                """, schema);

        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, limit, offset))
                .map(rows -> {
                    List<Message<T>> messages = new ArrayList<>();
                    for (Row row : rows) {
                        Message<T> message = PgNativeMessages.map(row, objectMapper, payloadType);
                        if (message != null) {
                            messages.add(message);
                        }
                    }
                    return messages;
                });
    }

    @Override
    public Future<Void> tail(MessageHandler<T> onMessage) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Browser is closed"));
        }
        if (onMessage == null) {
            return Future.failedFuture(new IllegalArgumentException("onMessage handler is required"));
        }
        if (observer != null) {
            return Future.failedFuture(new IllegalStateException("tail is already active on this browser"));
        }

        // Delegate to the dedicated non-destructive observer (watermark drain + LISTEN reconnect).
        // The browser stays a thin reader; the observer owns the LISTEN connection and lifecycle.
        PgNativeQueueObserver<T> obs = new PgNativeQueueObserver<>(
                topic, payloadType, schema, pool, poolAdapter, objectMapper, onMessage);
        this.observer = obs;
        return obs.start();
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void close() {
        closed = true;
        PgNativeQueueObserver<T> obs = this.observer;
        this.observer = null;
        if (obs != null) {
            // Stop the tail (UNLISTEN + close the dedicated connection); observe the failure.
            obs.stop().onFailure(err ->
                    logger.warn("Failed to stop tail observer for topic {}: {}", topic, err.getMessage()));
        }
        logger.debug("Closed browser for topic: {}", topic);
    }
}
