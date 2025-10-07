package dev.mars.peegeeq.examples.springbootpriority.events;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.examples.springbootpriority.model.Priority;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Trade settlement event for priority-based message processing.
 * 
 * Demonstrates strongly-typed events with priority metadata for
 * middle and back office trade settlement workflows.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public class TradeSettlementEvent {
    
    private final String eventId;
    private final String tradeId;
    private final String counterparty;
    private final BigDecimal amount;
    private final String currency;
    private final LocalDate settlementDate;
    private final String status;  // FAIL, AMEND, NEW
    private final Priority priority;
    private final String failureReason;  // For CRITICAL fails
    private final Instant timestamp;
    
    @JsonCreator
    public TradeSettlementEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("settlementDate") LocalDate settlementDate,
            @JsonProperty("status") String status,
            @JsonProperty("priority") String priorityStr,
            @JsonProperty("failureReason") String failureReason,
            @JsonProperty("timestamp") Instant timestamp) {
        this.eventId = eventId;
        this.tradeId = tradeId;
        this.counterparty = counterparty;
        this.amount = amount;
        this.currency = currency;
        this.settlementDate = settlementDate;
        this.status = status;
        this.priority = Priority.fromString(priorityStr);
        this.failureReason = failureReason;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }
    
    // Getters
    
    public String getEventId() {
        return eventId;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public String getCounterparty() {
        return counterparty;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public LocalDate getSettlementDate() {
        return settlementDate;
    }
    
    public String getStatus() {
        return status;
    }
    
    public Priority getPriority() {
        return priority;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TradeSettlementEvent that = (TradeSettlementEvent) o;
        return Objects.equals(eventId, that.eventId) &&
               Objects.equals(tradeId, that.tradeId) &&
               Objects.equals(counterparty, that.counterparty) &&
               Objects.equals(amount, that.amount) &&
               Objects.equals(currency, that.currency) &&
               Objects.equals(settlementDate, that.settlementDate) &&
               Objects.equals(status, that.status) &&
               priority == that.priority &&
               Objects.equals(failureReason, that.failureReason) &&
               Objects.equals(timestamp, that.timestamp);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(eventId, tradeId, counterparty, amount, currency, 
                          settlementDate, status, priority, failureReason, timestamp);
    }
    
    @Override
    public String toString() {
        return "TradeSettlementEvent{" +
               "eventId='" + eventId + '\'' +
               ", tradeId='" + tradeId + '\'' +
               ", counterparty='" + counterparty + '\'' +
               ", amount=" + amount +
               ", currency='" + currency + '\'' +
               ", settlementDate=" + settlementDate +
               ", status='" + status + '\'' +
               ", priority=" + priority +
               ", failureReason='" + failureReason + '\'' +
               ", timestamp=" + timestamp +
               '}';
    }
}

