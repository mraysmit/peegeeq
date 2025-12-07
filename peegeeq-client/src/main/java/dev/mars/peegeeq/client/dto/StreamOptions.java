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
 * Options for streaming events or messages.
 */
public class StreamOptions {

    private String eventType;
    private String aggregateId;
    private Instant fromTime;
    private boolean includeHistorical;
    private int bufferSize;

    public StreamOptions() {
        this.bufferSize = 100;
        this.includeHistorical = false;
    }

    // Getters and setters
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public Instant getFromTime() { return fromTime; }
    public void setFromTime(Instant fromTime) { this.fromTime = fromTime; }

    public boolean isIncludeHistorical() { return includeHistorical; }
    public void setIncludeHistorical(boolean includeHistorical) { this.includeHistorical = includeHistorical; }

    public int getBufferSize() { return bufferSize; }
    public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }

    // Fluent builder methods
    public StreamOptions withEventType(String eventType) {
        this.eventType = eventType;
        return this;
    }

    public StreamOptions withAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
        return this;
    }

    public StreamOptions withFromTime(Instant fromTime) {
        this.fromTime = fromTime;
        return this;
    }

    public StreamOptions withIncludeHistorical(boolean includeHistorical) {
        this.includeHistorical = includeHistorical;
        return this;
    }

    public StreamOptions withBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
    }

    /**
     * Creates default streaming options.
     */
    public static StreamOptions defaults() {
        return new StreamOptions();
    }
}

