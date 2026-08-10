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

/**
 * Result of appending an event to an event store.
 *
 * <p>Maps the storeEvent 201 payload {@code {message, eventStoreName, setupId,
 * eventId, eventType, version, transactionTime}} — the endpoint does not emit
 * the stored event itself (event-stores contract review, 2026-08-10).</p>
 *
 * @param eventStoreName the event store the event was appended to
 * @param setupId the setup identifier
 * @param eventId the stored event's identifier
 * @param eventType the stored event's type
 * @param version the stored event's version
 * @param transactionTime the transaction time the store recorded
 */
public record EventAppendResult(
    String eventStoreName,
    String setupId,
    String eventId,
    String eventType,
    long version,
    Instant transactionTime
) {
}
