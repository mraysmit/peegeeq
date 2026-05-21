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

package dev.mars.peegeeq.rest.dto;

import java.util.Map;

/**
 * Event store statistics response object.
 */
public class EventStoreStats {

    private final String eventStoreName;
    private final long totalEvents;
    private final long totalCorrections;
    private final Map<String, Long> eventCountsByType;

    public EventStoreStats(String eventStoreName, long totalEvents, long totalCorrections,
                           Map<String, Long> eventCountsByType) {
        this.eventStoreName = eventStoreName;
        this.totalEvents = totalEvents;
        this.totalCorrections = totalCorrections;
        this.eventCountsByType = eventCountsByType;
    }

    public String getEventStoreName() { return eventStoreName; }
    public long getTotalEvents() { return totalEvents; }
    public long getTotalCorrections() { return totalCorrections; }
    public Map<String, Long> getEventCountsByType() { return eventCountsByType; }
}
