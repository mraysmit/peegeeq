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
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import io.vertx.core.json.JsonObject;
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
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of QueueBrowser for outbox pattern queues.
 * Allows browsing messages without consuming them.
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
    private volatile boolean closed = false;

    public OutboxQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper) {
        this(topic, payloadType, pool, objectMapper, "peegeeq");
    }

    public OutboxQueueBrowser(String topic, Class<T> payloadType, Pool pool, ObjectMapper objectMapper, String schema) {
        this.topic = topic;
        this.payloadType = payloadType;
        this.pool = pool;
        this.objectMapper = objectMapper;
        this.schema = schema != null ? schema : "peegeeq";
    }

    @Override
    public CompletableFuture<List<Message<T>>> browse(int limit, int offset) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Browser is closed"));
        }

        String sql = """
                SELECT id, payload, headers, created_at, status, correlation_id
                FROM %s.outbox
                WHERE topic = $1
                ORDER BY id DESC
                LIMIT $2 OFFSET $3
                """.formatted(schema);

        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, limit, offset))
                .toCompletionStage()
                .toCompletableFuture()
                .thenApply(rows -> {
                    List<Message<T>> messages = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            String id = String.valueOf(row.getLong("id"));
                            JsonObject jsonPayload = row.getJsonObject("payload");
                            T payload;

                            // Handle primitive wrapper created by OutboxProducer
                            if ((payloadType == String.class || Number.class.isAssignableFrom(payloadType) || payloadType == Boolean.class)
                                    && jsonPayload.containsKey("value") && jsonPayload.size() == 1) {
                                Object value = jsonPayload.getValue("value");
                                if (payloadType == String.class) {
                                    payload = payloadType.cast(String.valueOf(value));
                                } else {
                                    payload = objectMapper.convertValue(value, payloadType);
                                }
                            } else {
                                payload = objectMapper.readValue(jsonPayload.encode(), payloadType);
                            }

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

                            String correlationId = row.getString("correlation_id");

                            messages.add(new OutboxMessage<>(id, payload, createdAt, headers, correlationId));
                        } catch (Exception e) {
                            logger.warn("Failed to parse message: {}", e.getMessage());
                        }
                    }
                    return messages;
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
