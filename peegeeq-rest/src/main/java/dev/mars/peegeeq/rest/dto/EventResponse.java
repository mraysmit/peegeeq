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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Response object for events.
 */
public class EventResponse {

    @JsonProperty("eventId")
    private String id;
    private String eventType;
    private Object eventData;
    @JsonProperty("validFrom")
    private Instant validFrom;
    private Instant validTo;
    private Instant transactionTime;
    private String correlationId;
    private String causationId;
    private String aggregateId;
    private int version;
    private Map<String, Object> metadata;

    public EventResponse() {}

    public EventResponse(String id, String eventType, Object eventData, Instant validFrom,
                         Instant validTo, Instant transactionTime, String correlationId,
                         String causationId, String aggregateId, int version, Map<String, Object> metadata) {
        this.id = id;
        this.eventType = eventType;
        this.eventData = eventData;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.transactionTime = transactionTime;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.aggregateId = aggregateId;
        this.version = version;
        this.metadata = metadata;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Object getEventData() { return eventData; }
    public void setEventData(Object eventData) { this.eventData = eventData; }

    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

    public Instant getValidTime() { return validFrom; }

    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }

    public Instant getTransactionTime() { return transactionTime; }
    public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
