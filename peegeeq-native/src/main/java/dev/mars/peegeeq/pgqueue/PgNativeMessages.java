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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared mapping of a {@code queue_messages} row to a {@link Message}.
 *
 * <p>Extracted so the two non-destructive read paths — {@link PgNativeQueueBrowser#browse(int, int)}
 * (point-in-time) and {@link PgNativeQueueObserver} (live tail) — map rows identically and cannot
 * drift apart. The {@code SELECT} columns these readers use must stay in sync:
 * {@code id, payload, headers, created_at, status}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
final class PgNativeMessages {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeMessages.class);

    private PgNativeMessages() {
    }

    /**
     * Maps one row to a {@link Message}, or returns {@code null} (logged at WARN) if the row
     * cannot be parsed. The caller skips {@code null} results.
     */
    static <T> Message<T> map(Row row, ObjectMapper objectMapper, Class<T> payloadType) {
        try {
            String id = String.valueOf(row.getLong("id"));
            T payload = parsePayload(row.getJsonObject("payload"), objectMapper, payloadType);

            Map<String, String> headers = new HashMap<>();
            JsonObject headersJson = row.getJsonObject("headers");
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
     * Reverses {@code PgNativeQueueProducer.toJsonObject}: scalar/collection payloads are stored
     * wrapped as {@code {"value": …}}; complex objects are stored as their JSON. Mirrors the
     * working {@code PgNativeQueueConsumer.parsePayloadFromJsonObject} exactly so the read side
     * matches the write side.
     */
    private static <T> T parsePayload(JsonObject payload, ObjectMapper objectMapper, Class<T> payloadType)
            throws Exception {
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        // Simple value wrapped in {"value": ...} by the producer.
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                return payloadType.cast(value);
            }
            if (value instanceof JsonObject) {
                return objectMapper.readValue(((JsonObject) value).encode(), payloadType);
            }
            if (value instanceof JsonArray) {
                return objectMapper.readValue(((JsonArray) value).encode(), payloadType);
            }
            if (value instanceof Number || value instanceof CharSequence || value instanceof Boolean) {
                return objectMapper.convertValue(value, payloadType);
            }
        }

        // Complex object: deserialize the whole payload with the configured ObjectMapper.
        return objectMapper.readValue(payload.encode(), payloadType);
    }
}
