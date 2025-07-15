package dev.mars.peegeeq.api;

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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Simple implementation of the BiTemporalEvent interface.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities with
 * bi-temporal event sourcing support.
 * 
 * @param <T> The type of event payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class SimpleBiTemporalEvent<T> implements BiTemporalEvent<T> {
    
    private final String eventId;
    private final String eventType;
    private final T payload;
    private final Instant validTime;
    private final Instant transactionTime;
    private final long version;
    private final String previousVersionId;
    private final Map<String, String> headers;
    private final String correlationId;
    private final String aggregateId;
    private final boolean isCorrection;
    private final String correctionReason;
    
    /**
     * Creates a new SimpleBiTemporalEvent.
     *
     * @param eventId The unique event identifier
     * @param eventType The event type
     * @param payload The event payload
     * @param validTime When the event actually happened
     * @param transactionTime When the event was recorded
     * @param version The event version
     * @param previousVersionId The previous version ID (for corrections)
     * @param headers Event headers
     * @param correlationId Correlation ID
     * @param aggregateId Aggregate ID
     * @param isCorrection Whether this is a correction
     * @param correctionReason Reason for correction (if applicable)
     */
    public SimpleBiTemporalEvent(String eventId, String eventType, T payload, 
                                Instant validTime, Instant transactionTime, long version,
                                String previousVersionId, Map<String, String> headers,
                                String correlationId, String aggregateId,
                                boolean isCorrection, String correctionReason) {
        this.eventId = Objects.requireNonNull(eventId, "Event ID cannot be null");
        this.eventType = Objects.requireNonNull(eventType, "Event type cannot be null");
        this.payload = Objects.requireNonNull(payload, "Payload cannot be null");
        this.validTime = Objects.requireNonNull(validTime, "Valid time cannot be null");
        this.transactionTime = Objects.requireNonNull(transactionTime, "Transaction time cannot be null");
        this.version = version;
        this.previousVersionId = previousVersionId;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.correlationId = correlationId;
        this.aggregateId = aggregateId;
        this.isCorrection = isCorrection;
        this.correctionReason = correctionReason;
        
        // Validation
        if (version <= 0) {
            throw new IllegalArgumentException("Version must be positive");
        }
        if (isCorrection && correctionReason == null) {
            throw new IllegalArgumentException("Correction reason is required for correction events");
        }
        if (!isCorrection && correctionReason != null) {
            throw new IllegalArgumentException("Correction reason should only be set for correction events");
        }
        if (version == 1 && previousVersionId != null) {
            throw new IllegalArgumentException("First version cannot have a previous version ID");
        }
        if (version > 1 && previousVersionId == null) {
            throw new IllegalArgumentException("Versions after 1 must have a previous version ID");
        }
    }
    
    /**
     * Creates a new SimpleBiTemporalEvent with minimal parameters.
     *
     * @param eventId The unique event identifier
     * @param eventType The event type
     * @param payload The event payload
     * @param validTime When the event actually happened
     * @param transactionTime When the event was recorded
     */
    public SimpleBiTemporalEvent(String eventId, String eventType, T payload, 
                                Instant validTime, Instant transactionTime) {
        this(eventId, eventType, payload, validTime, transactionTime, 1L, null, 
             Map.of(), null, null, false, null);
    }
    
    /**
     * Creates a new SimpleBiTemporalEvent with headers and correlation.
     *
     * @param eventId The unique event identifier
     * @param eventType The event type
     * @param payload The event payload
     * @param validTime When the event actually happened
     * @param transactionTime When the event was recorded
     * @param headers Event headers
     * @param correlationId Correlation ID
     * @param aggregateId Aggregate ID
     */
    public SimpleBiTemporalEvent(String eventId, String eventType, T payload, 
                                Instant validTime, Instant transactionTime,
                                Map<String, String> headers, String correlationId, 
                                String aggregateId) {
        this(eventId, eventType, payload, validTime, transactionTime, 1L, null,
             headers, correlationId, aggregateId, false, null);
    }
    
    @Override
    public String getEventId() {
        return eventId;
    }
    
    @Override
    public String getEventType() {
        return eventType;
    }
    
    @Override
    public T getPayload() {
        return payload;
    }
    
    @Override
    public Instant getValidTime() {
        return validTime;
    }
    
    @Override
    public Instant getTransactionTime() {
        return transactionTime;
    }
    
    @Override
    public long getVersion() {
        return version;
    }
    
    @Override
    public String getPreviousVersionId() {
        return previousVersionId;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    @Override
    public String getCorrelationId() {
        return correlationId;
    }
    
    @Override
    public String getAggregateId() {
        return aggregateId;
    }
    
    @Override
    public boolean isCorrection() {
        return isCorrection;
    }
    
    @Override
    public String getCorrectionReason() {
        return correctionReason;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SimpleBiTemporalEvent<?> that = (SimpleBiTemporalEvent<?>) o;
        return version == that.version &&
               isCorrection == that.isCorrection &&
               Objects.equals(eventId, that.eventId) &&
               Objects.equals(eventType, that.eventType) &&
               Objects.equals(payload, that.payload) &&
               Objects.equals(validTime, that.validTime) &&
               Objects.equals(transactionTime, that.transactionTime) &&
               Objects.equals(previousVersionId, that.previousVersionId) &&
               Objects.equals(headers, that.headers) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(aggregateId, that.aggregateId) &&
               Objects.equals(correctionReason, that.correctionReason);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(eventId, eventType, payload, validTime, transactionTime,
                          version, previousVersionId, headers, correlationId, 
                          aggregateId, isCorrection, correctionReason);
    }
    
    @Override
    public String toString() {
        return "SimpleBiTemporalEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", validTime=" + validTime +
                ", transactionTime=" + transactionTime +
                ", version=" + version +
                ", previousVersionId='" + previousVersionId + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", isCorrection=" + isCorrection +
                ", correctionReason='" + correctionReason + '\'' +
                ", headers=" + headers +
                '}';
    }
}
