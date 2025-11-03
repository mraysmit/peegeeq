package dev.mars.peegeeq.examples.springboot2bitemporal.events;

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
import dev.mars.peegeeq.examples.springboot2bitemporal.model.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Represents a settlement instruction event in the bi-temporal event store.
 * 
 * <p>This event captures settlement instruction lifecycle events following the
 * {entity}.{action}.{state} naming pattern:
 * <ul>
 *   <li>instruction.settlement.submitted</li>
 *   <li>instruction.settlement.matched</li>
 *   <li>instruction.settlement.confirmed</li>
 *   <li>instruction.settlement.failed</li>
 *   <li>instruction.settlement.corrected</li>
 * </ul>
 * 
 * <p>The event is immutable and serialized to JSONB in PostgreSQL.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public class SettlementEvent {
    
    private final String instructionId;
    private final String tradeId;
    private final String counterparty;
    private final BigDecimal amount;
    private final String currency;
    private final LocalDate settlementDate;
    private final SettlementStatus status;
    private final String failureReason;
    private final Instant eventTime;
    
    @JsonCreator
    public SettlementEvent(
            @JsonProperty("instructionId") String instructionId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("settlementDate") LocalDate settlementDate,
            @JsonProperty("status") SettlementStatus status,
            @JsonProperty("failureReason") String failureReason,
            @JsonProperty("eventTime") Instant eventTime) {
        this.instructionId = instructionId;
        this.tradeId = tradeId;
        this.counterparty = counterparty;
        this.amount = amount;
        this.currency = currency;
        this.settlementDate = settlementDate;
        this.status = status;
        this.failureReason = failureReason;
        this.eventTime = eventTime;
    }
    
    public String getInstructionId() {
        return instructionId;
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
    
    public SettlementStatus getStatus() {
        return status;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public Instant getEventTime() {
        return eventTime;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SettlementEvent that = (SettlementEvent) o;
        return Objects.equals(instructionId, that.instructionId) &&
               Objects.equals(tradeId, that.tradeId) &&
               Objects.equals(counterparty, that.counterparty) &&
               Objects.equals(amount, that.amount) &&
               Objects.equals(currency, that.currency) &&
               Objects.equals(settlementDate, that.settlementDate) &&
               status == that.status &&
               Objects.equals(failureReason, that.failureReason) &&
               Objects.equals(eventTime, that.eventTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(instructionId, tradeId, counterparty, amount, currency, 
                          settlementDate, status, failureReason, eventTime);
    }
    
    @Override
    public String toString() {
        return "SettlementEvent{" +
               "instructionId='" + instructionId + '\'' +
               ", tradeId='" + tradeId + '\'' +
               ", counterparty='" + counterparty + '\'' +
               ", amount=" + amount +
               ", currency='" + currency + '\'' +
               ", settlementDate=" + settlementDate +
               ", status=" + status +
               ", failureReason='" + failureReason + '\'' +
               ", eventTime=" + eventTime +
               '}';
    }
}

