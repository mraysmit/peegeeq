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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a query for bi-temporal events.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities with
 * bi-temporal event sourcing support.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class EventQuery {
    
    private final String eventType;
    private final String aggregateId;
    private final String correlationId;
    private final TemporalRange validTimeRange;
    private final TemporalRange transactionTimeRange;
    private final Map<String, String> headerFilters;
    private final int limit;
    private final int offset;
    private final SortOrder sortOrder;
    private final boolean includeCorrections;
    private final Long minVersion;
    private final Long maxVersion;
    
    /**
     * Sort order for query results.
     */
    public enum SortOrder {
        VALID_TIME_ASC,
        VALID_TIME_DESC,
        TRANSACTION_TIME_ASC,
        TRANSACTION_TIME_DESC,
        VERSION_ASC,
        VERSION_DESC
    }
    
    private EventQuery(Builder builder) {
        this.eventType = builder.eventType;
        this.aggregateId = builder.aggregateId;
        this.correlationId = builder.correlationId;
        this.validTimeRange = builder.validTimeRange;
        this.transactionTimeRange = builder.transactionTimeRange;
        this.headerFilters = builder.headerFilters != null ? Map.copyOf(builder.headerFilters) : Map.of();
        this.limit = builder.limit;
        this.offset = builder.offset;
        this.sortOrder = builder.sortOrder;
        this.includeCorrections = builder.includeCorrections;
        this.minVersion = builder.minVersion;
        this.maxVersion = builder.maxVersion;
    }
    
    /**
     * Creates a new query builder.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a query for all events.
     *
     * @return A new query for all events
     */
    public static EventQuery all() {
        return builder().build();
    }
    
    /**
     * Creates a query for events of a specific type.
     *
     * @param eventType The event type
     * @return A new query
     */
    public static EventQuery forEventType(String eventType) {
        return builder().eventType(eventType).build();
    }
    
    /**
     * Creates a query for events of a specific aggregate.
     *
     * @param aggregateId The aggregate ID
     * @return A new query
     */
    public static EventQuery forAggregate(String aggregateId) {
        return builder().aggregateId(aggregateId).build();
    }
    
    /**
     * Creates a point-in-time query for a specific valid time.
     *
     * @param validTime The valid time
     * @return A new query
     */
    public static EventQuery asOfValidTime(Instant validTime) {
        return builder()
            .validTimeRange(TemporalRange.until(validTime))
            .transactionTimeRange(TemporalRange.all())
            .build();
    }
    
    /**
     * Creates a point-in-time query for a specific transaction time.
     *
     * @param transactionTime The transaction time
     * @return A new query
     */
    public static EventQuery asOfTransactionTime(Instant transactionTime) {
        return builder()
            .transactionTimeRange(TemporalRange.until(transactionTime))
            .build();
    }
    
    // Getters
    public Optional<String> getEventType() { return Optional.ofNullable(eventType); }
    public Optional<String> getAggregateId() { return Optional.ofNullable(aggregateId); }
    public Optional<String> getCorrelationId() { return Optional.ofNullable(correlationId); }
    public Optional<TemporalRange> getValidTimeRange() { return Optional.ofNullable(validTimeRange); }
    public Optional<TemporalRange> getTransactionTimeRange() { return Optional.ofNullable(transactionTimeRange); }
    public Map<String, String> getHeaderFilters() { return headerFilters; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }
    public SortOrder getSortOrder() { return sortOrder; }
    public boolean isIncludeCorrections() { return includeCorrections; }
    public Optional<Long> getMinVersion() { return Optional.ofNullable(minVersion); }
    public Optional<Long> getMaxVersion() { return Optional.ofNullable(maxVersion); }
    
    /**
     * Builder for EventQuery.
     */
    public static class Builder {
        private String eventType;
        private String aggregateId;
        private String correlationId;
        private TemporalRange validTimeRange;
        private TemporalRange transactionTimeRange;
        private Map<String, String> headerFilters;
        private int limit = 1000; // Default limit
        private int offset = 0;
        private SortOrder sortOrder = SortOrder.TRANSACTION_TIME_ASC;
        private boolean includeCorrections = true;
        private Long minVersion;
        private Long maxVersion;
        
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }
        
        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }
        
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }
        
        public Builder validTimeRange(TemporalRange range) {
            this.validTimeRange = range;
            return this;
        }
        
        public Builder transactionTimeRange(TemporalRange range) {
            this.transactionTimeRange = range;
            return this;
        }
        
        public Builder headerFilters(Map<String, String> filters) {
            this.headerFilters = filters;
            return this;
        }
        
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("Limit must be positive");
            }
            this.limit = limit;
            return this;
        }
        
        public Builder offset(int offset) {
            if (offset < 0) {
                throw new IllegalArgumentException("Offset cannot be negative");
            }
            this.offset = offset;
            return this;
        }
        
        public Builder sortOrder(SortOrder sortOrder) {
            this.sortOrder = Objects.requireNonNull(sortOrder, "Sort order cannot be null");
            return this;
        }
        
        public Builder includeCorrections(boolean includeCorrections) {
            this.includeCorrections = includeCorrections;
            return this;
        }
        
        public Builder versionRange(Long minVersion, Long maxVersion) {
            this.minVersion = minVersion;
            this.maxVersion = maxVersion;
            return this;
        }
        
        public EventQuery build() {
            return new EventQuery(this);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventQuery that = (EventQuery) o;
        return limit == that.limit &&
               offset == that.offset &&
               includeCorrections == that.includeCorrections &&
               Objects.equals(eventType, that.eventType) &&
               Objects.equals(aggregateId, that.aggregateId) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(validTimeRange, that.validTimeRange) &&
               Objects.equals(transactionTimeRange, that.transactionTimeRange) &&
               Objects.equals(headerFilters, that.headerFilters) &&
               sortOrder == that.sortOrder &&
               Objects.equals(minVersion, that.minVersion) &&
               Objects.equals(maxVersion, that.maxVersion);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(eventType, aggregateId, correlationId, validTimeRange, 
                          transactionTimeRange, headerFilters, limit, offset, 
                          sortOrder, includeCorrections, minVersion, maxVersion);
    }
    
    @Override
    public String toString() {
        return "EventQuery{" +
                "eventType='" + eventType + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", validTimeRange=" + validTimeRange +
                ", transactionTimeRange=" + transactionTimeRange +
                ", headerFilters=" + headerFilters +
                ", limit=" + limit +
                ", offset=" + offset +
                ", sortOrder=" + sortOrder +
                ", includeCorrections=" + includeCorrections +
                ", minVersion=" + minVersion +
                ", maxVersion=" + maxVersion +
                '}';
    }
}
