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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Request object for storing events.
 *
 * <p>The event store records one valid time per event. The request has no {@code validTo}
 * property, so the parser refuses a request that sends one. Before this rule {@code validTo} was
 * accepted and dropped.</p>
 */
public class EventRequest {

    private String aggregateId;
    private String eventType;
    private Object eventData;
    private Object payload;  // Alias for eventData for API flexibility
    private Instant validFrom;
    private String validTime;  // String version of validFrom for API flexibility
    private String correlationId;
    private String causationId;
    private Map<String, Object> metadata;

    // Getters and setters
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Object getEventData() {
        // Return eventData if set, otherwise return payload
        return eventData != null ? eventData : payload;
    }
    public void setEventData(Object eventData) { this.eventData = eventData; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    /**
     * Returns the valid time this request names: {@code validFrom}, or {@code validTime} read
     * as an ISO-8601 instant. Returns null when the request names neither.
     *
     * <p>A valid time is business data of an append-only store. A value that cannot be read is
     * refused, never replaced: before this rule an unreadable {@code validTime} was stored as the
     * time of the request and answered 201 (finding 18).</p>
     *
     * @throws IllegalArgumentException if {@code validTime} is not an ISO-8601 instant, or if
     *                                  {@code validFrom} and {@code validTime} name different instants
     */
    public Instant getValidFrom() {
        Instant parsedValidTime = null;
        if (validTime != null) {
            try {
                parsedValidTime = Instant.parse(validTime);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "validTime is not an ISO-8601 instant: '" + validTime + "'", e);
            }
        }
        if (validFrom != null && parsedValidTime != null && !validFrom.equals(parsedValidTime)) {
            throw new IllegalArgumentException("validFrom (" + validFrom + ") and validTime ("
                    + parsedValidTime + ") name different instants");
        }
        return validFrom != null ? validFrom : parsedValidTime;
    }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

    public String getValidTime() { return validTime; }
    public void setValidTime(String validTime) { this.validTime = validTime; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
