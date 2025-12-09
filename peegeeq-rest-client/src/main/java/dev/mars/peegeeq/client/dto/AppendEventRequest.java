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
import java.util.HashMap;
import java.util.Map;

/**
 * Request to append an event to an event store.
 */
public class AppendEventRequest {

    private String eventType;
    private Object payload;
    private Instant validTime;
    private Map<String, String> headers;
    private String correlationId;
    private String aggregateId;

    public AppendEventRequest() {
        this.headers = new HashMap<>();
    }

    public AppendEventRequest(String eventType, Object payload) {
        this();
        this.eventType = eventType;
        this.payload = payload;
    }

    // Getters and setters
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public Instant getValidTime() { return validTime; }
    public void setValidTime(Instant validTime) { this.validTime = validTime; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    // Fluent builder methods
    public AppendEventRequest withEventType(String eventType) {
        this.eventType = eventType;
        return this;
    }

    public AppendEventRequest withPayload(Object payload) {
        this.payload = payload;
        return this;
    }

    public AppendEventRequest withValidTime(Instant validTime) {
        this.validTime = validTime;
        return this;
    }

    public AppendEventRequest withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public AppendEventRequest withCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public AppendEventRequest withAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
        return this;
    }
}

