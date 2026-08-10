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

import java.util.Map;

/**
 * Statistics for an event store.
 *
 * <p>Maps the stats payload's nested {@code stats} object
 * {@code {eventStoreName, totalEvents, totalCorrections, eventCountsByType}}
 * plus the wrapper's {@code setupId}. The old shape's uniqueEventIds/
 * oldestEventTime/newestEventTime/eventsPerSecond fields had no source in any
 * payload and were deleted rather than default-filled, along with the
 * {@code basic()} factory that fabricated them (event-stores contract review,
 * 2026-08-10).</p>
 *
 * @param storeName the event store name (wire key {@code stats.eventStoreName})
 * @param setupId the setup identifier
 * @param totalEvents total events stored
 * @param totalCorrections total corrections stored
 * @param eventCountsByType per-event-type counts, or null when the payload carries none
 */
public record EventStoreStats(
    String storeName,
    String setupId,
    long totalEvents,
    long totalCorrections,
    Map<String, Long> eventCountsByType
) {
}
