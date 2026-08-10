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

import java.util.List;

/**
 * Result of querying events from an event store.
 *
 * <p>Maps the query payload's {@code events} array, {@code totalCount} and
 * {@code hasMore} keys. The old shape held the non-instantiable
 * {@code BiTemporalEvent} interface and could never be populated from the wire
 * (event-stores contract review, 2026-08-10).</p>
 *
 * @param events the page of events
 * @param total the endpoint's totalCount across all pages
 * @param hasMore whether more events exist beyond this page
 */
public record EventQueryResult(
    List<EventInfo> events,
    long total,
    boolean hasMore
) {
    /**
     * Returns true if the result is empty.
     */
    public boolean isEmpty() {
        return events == null || events.isEmpty();
    }

    /**
     * Returns the number of events in this result.
     */
    public int size() {
        return events == null ? 0 : events.size();
    }
}
