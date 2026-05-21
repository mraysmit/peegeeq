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

import dev.mars.peegeeq.api.EventQuery;

import java.time.Instant;

/**
 * Query parameters for event store queries with full bi-temporal support.
 */
public class EventQueryParams {

    // Basic filters
    private String eventType;
    private String aggregateId;
    private String correlationId;
    private String causationId; // Legacy support

    // Valid time range (business time)
    private Instant validTimeFrom;
    private Instant validTimeTo;

    // Transaction time range (system time - bi-temporal)
    private Instant transactionTimeFrom;
    private Instant transactionTimeTo;
    private Instant asOfTransactionTime; // Point-in-time query

    // Sorting and filtering
    private EventQuery.SortOrder sortOrder = EventQuery.SortOrder.TRANSACTION_TIME_ASC;
    private boolean includeCorrections = true;
    private Long minVersion;
    private Long maxVersion;

    // Pagination
    private int limit = 100;
    private int offset = 0;

    // Getters and setters
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public Instant getValidTimeFrom() { return validTimeFrom; }
    public void setValidTimeFrom(Instant validTimeFrom) { this.validTimeFrom = validTimeFrom; }

    public Instant getValidTimeTo() { return validTimeTo; }
    public void setValidTimeTo(Instant validTimeTo) { this.validTimeTo = validTimeTo; }

    public Instant getTransactionTimeFrom() { return transactionTimeFrom; }
    public void setTransactionTimeFrom(Instant transactionTimeFrom) { this.transactionTimeFrom = transactionTimeFrom; }

    public Instant getTransactionTimeTo() { return transactionTimeTo; }
    public void setTransactionTimeTo(Instant transactionTimeTo) { this.transactionTimeTo = transactionTimeTo; }

    public Instant getAsOfTransactionTime() { return asOfTransactionTime; }
    public void setAsOfTransactionTime(Instant asOfTransactionTime) { this.asOfTransactionTime = asOfTransactionTime; }

    public EventQuery.SortOrder getSortOrder() { return sortOrder; }
    public void setSortOrder(EventQuery.SortOrder sortOrder) { this.sortOrder = sortOrder; }

    public boolean isIncludeCorrections() { return includeCorrections; }
    public void setIncludeCorrections(boolean includeCorrections) { this.includeCorrections = includeCorrections; }

    public Long getMinVersion() { return minVersion; }
    public void setMinVersion(Long minVersion) { this.minVersion = minVersion; }

    public Long getMaxVersion() { return maxVersion; }
    public void setMaxVersion(Long maxVersion) { this.maxVersion = maxVersion; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = Math.max(1, Math.min(1000, limit)); }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = Math.max(0, offset); }

    // Legacy support - backward compatibility
    @Deprecated
    public Instant getFromTime() { return validTimeFrom; }
    @Deprecated
    public void setFromTime(Instant fromTime) { this.validTimeFrom = fromTime; }

    @Deprecated
    public Instant getToTime() { return validTimeTo; }
    @Deprecated
    public void setToTime(Instant toTime) { this.validTimeTo = toTime; }
}
