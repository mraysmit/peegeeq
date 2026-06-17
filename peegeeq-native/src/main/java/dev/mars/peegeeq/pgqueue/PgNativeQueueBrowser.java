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
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of QueueBrowser for native PostgreSQL queues.
 * Allows browsing messages without consuming them.
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
    // Adapter is used only by tail() to obtain a dedicated (non-pooled) LISTEN connection.
    private final VertxPoolAdapter poolAdapter;
    private volatile boolean closed = false;
    // Dedicated LISTEN connection for tail() — non-pooled, closed in close().
    private volatile PgConnection tailConnection;

    // The schema-defaulting constructor was removed deliberately: the browser's SQL is
    // schema-qualified, and PeeGeeQ has no default schema — callers pass it explicitly.

    public PgNativeQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper,
            String schema, VertxPoolAdapter poolAdapter) {
        this.poolAdapter = poolAdapter;
        this.topic = topic;
        this.payloadType = payloadType;
        this.pool = pool;
        this.objectMapper = objectMapper;
        this.schema = java.util.Objects.requireNonNull(schema,
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
                        Message<T> message = mapRow(row);
                        if (message != null) {
                            messages.add(message);
                        }
                    }
                    return messages;
                });
    }

    /**
     * Maps a {@code queue_messages} row to a {@link Message}. Returns null if the row cannot be
     * parsed (logged at WARN). Shared by {@link #browse(int, int)} and {@link #readById(long)}.
     */
    private Message<T> mapRow(Row row) {
        try {
            String id = String.valueOf(row.getLong("id"));
            String payloadJson = row.getJsonObject("payload").encode();
            T payload = objectMapper.readValue(payloadJson, payloadType);

            Map<String, String> headers = new HashMap<>();
            var headersJson = row.getJsonObject("headers");
            if (headersJson != null) {
                for (String key : headersJson.fieldNames()) {
                    headers.put(key, headersJson.getString(key));
                }
            }

            Instant createdAt = row.getLocalDateTime("created_at") != null
                    ? row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC)
                    : Instant.now();

            return new PgNativeMessage<>(id, payload, createdAt, headers);
        } catch (Exception e) {
            logger.warn("Failed to parse message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Non-destructive read of a single message by its database id. A plain SELECT — no
     * FOR UPDATE, no status change, no delete — so it never consumes the message.
     */
    private Future<Message<T>> readById(long id) {
        String sql = String.format("""
                SELECT id, payload, headers, created_at, status
                FROM %s.queue_messages
                WHERE topic = $1 AND id = $2
                """, schema);
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, id))
                .map(rows -> {
                    var it = rows.iterator();
                    return it.hasNext() ? mapRow(it.next()) : null;
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
        if (tailConnection != null) {
            return Future.failedFuture(new IllegalStateException("tail is already active on this browser"));
        }

        final String channel = NativeQueueChannels.channelFor(schema, topic);

        // Dedicated, non-pooled connection for LISTEN (pooled connections are recycled and must
        // not hold a LISTEN). On each NOTIFY the payload is the new message's DB id (see
        // PgNativeQueueProducer's pg_notify); we read that row non-destructively and push it.
        return poolAdapter.connectDedicated().compose(conn -> {
            this.tailConnection = conn;

            conn.notificationHandler(notification -> {
                if (!channel.equals(notification.getChannel())) {
                    return;
                }
                final long id;
                try {
                    id = Long.parseLong(notification.getPayload().trim());
                } catch (NumberFormatException e) {
                    logger.warn("tail: unexpected NOTIFY payload '{}' on channel {}",
                            notification.getPayload(), channel);
                    return;
                }
                readById(id)
                        .onSuccess(message -> {
                            if (message != null) {
                                onMessage.handle(message)
                                        .onFailure(err -> logger.warn("tail: handler failed for message {}: {}",
                                                id, err.getMessage()));
                            }
                        })
                        .onFailure(err -> logger.warn("tail: failed to read message id {} on {}: {}",
                                id, channel, err.getMessage()));
            });

            return conn.query("LISTEN " + channel).execute()
                    .onSuccess(v -> logger.info("tail: observing channel {} (non-destructive)", channel))
                    .<Void>mapEmpty()
                    .onFailure(err -> {
                        logger.error("tail: LISTEN failed on {}: {}", channel, err.getMessage());
                        this.tailConnection = null;
                        conn.close().onFailure(closeErr ->
                                logger.warn("tail: failed to close connection after LISTEN error: {}",
                                        closeErr.getMessage()));
                    });
        });
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void close() {
        closed = true;
        logger.debug("Closed browser for topic: {}", topic);
    }
}
