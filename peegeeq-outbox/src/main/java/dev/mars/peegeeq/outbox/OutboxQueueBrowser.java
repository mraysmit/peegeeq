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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of QueueBrowser for outbox pattern queues.
 * Allows browsing messages without consuming them.
 *
 * <p>{@link #browse(int, int)} is the point-in-time non-destructive read; {@link #tail(MessageHandler)}
 * is the live non-destructive observe, delegated to a poll-based {@link OutboxQueueObserver} (the
 * outbox has no insert NOTIFY). Neither path acks, locks, or removes messages. {@code tail} requires
 * a Vert.x-aware browser — create it via {@code OutboxFactory.createBrowser(...)}.
 *
 * @param <T> The type of message payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxQueueBrowser<T> implements QueueBrowser<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueBrowser.class);

    private final String topic;
    private final Class<T> payloadType;
    private final Pool pool;
    private final ObjectMapper objectMapper;
    private final String schema;
    // Used only by tail() to schedule the poll; null for direct-construction callers (tail unavailable).
    private final Vertx vertx;
    private volatile boolean closed = false;
    // The live tail observer (one per browser); null until tail() is called, stopped in close().
    private volatile OutboxQueueObserver<T> observer;

    public OutboxQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper) {
        this(topic, payloadType, pool, objectMapper, null, null);
    }

    public OutboxQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper, String schema) {
        this(topic, payloadType, pool, objectMapper, schema, null);
    }

    public OutboxQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper,
            String schema, Vertx vertx) {
        this.topic = topic;
        this.payloadType = payloadType;
        this.pool = pool;
        this.objectMapper = objectMapper;
        // null schema  unqualified SQL (relies on search_path); non-null  schema-qualified SQL
        this.schema = schema;
        this.vertx = vertx;
    }

    @Override
    public Future<List<Message<T>>> browse(int limit, int offset) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Browser is closed"));
        }

        String tableRef = schema != null ? OutboxFactory.quoteIdentifier(schema) + ".outbox" : "outbox";
        String sql = """
                SELECT id, payload, headers, created_at, status, correlation_id
                FROM %s
                WHERE topic = $1
                ORDER BY id DESC
                LIMIT $2 OFFSET $3
                """.formatted(tableRef);

        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, limit, offset))
                .map(rows -> {
                    List<Message<T>> messages = new ArrayList<>();
                    for (Row row : rows) {
                        Message<T> message = OutboxMessages.map(row, objectMapper, payloadType);
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
        if (vertx == null) {
            return Future.failedFuture(new IllegalStateException(
                    "tail requires a Vert.x-aware browser; create it via OutboxFactory.createBrowser(...)"));
        }
        if (observer != null) {
            return Future.failedFuture(new IllegalStateException("tail is already active on this browser"));
        }

        // Delegate to the dedicated non-destructive poll observer (watermark drain over a periodic
        // timer). The browser stays a thin reader; the observer owns the poll lifecycle.
        OutboxQueueObserver<T> obs = new OutboxQueueObserver<>(
                topic, payloadType, schema, pool, vertx, objectMapper, onMessage);
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
        OutboxQueueObserver<T> obs = this.observer;
        this.observer = null;
        if (obs != null) {
            obs.stop().onFailure(err ->
                    logger.warn("Failed to stop tail observer for topic {}: {}", topic, err.getMessage()));
        }
        logger.debug("Closed browser for topic: {}", topic);
    }
}
