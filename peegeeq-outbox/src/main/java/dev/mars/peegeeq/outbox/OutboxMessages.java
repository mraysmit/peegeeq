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
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared mapping of an {@code outbox} row to a {@link Message}.
 *
 * <p>Extracted so the two non-destructive read paths — {@link OutboxQueueBrowser#browse(int, int)}
 * (point-in-time) and {@link OutboxQueueObserver} (live poll-tail) — map rows identically and cannot
 * drift apart. The {@code SELECT} columns these readers use must stay in sync:
 * {@code id, payload, headers, created_at, status, correlation_id}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
final class OutboxMessages {

    private static final Logger logger = LoggerFactory.getLogger(OutboxMessages.class);

    private OutboxMessages() {
    }

    /**
     * Maps one row to a {@link Message} (an {@link OutboxMessage}), or returns {@code null}
     * (logged at ERROR) if the row cannot be parsed. The caller skips {@code null} results.
     * Reverses {@code OutboxProducer.toJsonObject}: scalars are stored wrapped as
     * {@code {"value": …}}; complex objects are stored as their JSON.
     */
    static <T> Message<T> map(Row row, ObjectMapper objectMapper, Class<T> payloadType) {
        try {
            String id = String.valueOf(row.getLong("id"));
            JsonObject jsonPayload = row.getJsonObject("payload");
            T payload;

            // Handle primitive wrapper created by OutboxProducer ({"value": <scalar>}).
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
            JsonObject headersJson = row.getJsonObject("headers");
            if (headersJson != null) {
                for (String key : headersJson.fieldNames()) {
                    headers.put(key, headersJson.getString(key));
                }
            }

            Instant createdAt = row.getLocalDateTime("created_at") != null
                    ? row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC)
                    : Instant.now();

            String correlationId = row.getString("correlation_id");

            return new OutboxMessage<>(id, payload, createdAt, headers, correlationId);
        } catch (Exception e) {
            logger.error("Failed to parse message: {}", e.getMessage());
            return null;
        }
    }
}
