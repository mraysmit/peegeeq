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

package dev.mars.peegeeq.client.dto;

import java.time.Instant;
import java.util.Map;

/**
 * A single bi-temporal event mapped from the event-store endpoints' payloads.
 *
 * <p>The query/get/versions/as-of endpoints emit event objects with keys
 * {@code {eventId, eventType, eventData, validFrom, validTo, transactionTime,
 * correlationId, causationId, aggregateId, version, metadata}}; the SSE stream
 * emits per-event frames with keys {@code {eventId, eventType, aggregateId,
 * payload, validTime, transactionTime, version, correlationId, headers}}. Both
 * shapes map into this record; keys a payload does not carry map to null, never
 * defaults (event-stores contract review, 2026-08-10).</p>
 *
 * @param id the event identifier (wire key {@code eventId})
 * @param eventType the event type
 * @param eventData the event payload; a JSON object arrives as {@link io.vertx.core.json.JsonObject}
 * @param validFrom the valid-time start
 * @param validTo the valid-time end; the endpoints currently always emit null
 * @param transactionTime the transaction time
 * @param correlationId the correlation id, or null
 * @param causationId the causation id, or null; the SSE frame carries no causationId
 * @param aggregateId the aggregate id, or null
 * @param version the event version
 * @param metadata the event metadata (the server's headers map), or null when the payload carries none
 */
public record EventInfo(
    String id,
    String eventType,
    Object eventData,
    Instant validFrom,
    Instant validTo,
    Instant transactionTime,
    String correlationId,
    String causationId,
    String aggregateId,
    long version,
    Map<String, String> metadata
) {
}
