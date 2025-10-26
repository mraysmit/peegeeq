package dev.mars.peegeeq.examples.springbootpriority.model;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Trade settlement domain model.
 * 
 * Plain POJO with manual SQL mapping (no JPA/R2DBC annotations).
 * Represents a trade settlement record in the database.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public class Trade {
    
    private String id;
    private String tradeId;
    private String counterparty;
    private BigDecimal amount;
    private String currency;
    private LocalDate settlementDate;
    private String status;
    private Priority priority;
    private String failureReason;
    private Instant createdAt;
    private Instant processedAt;
    private String processedBy;
    
    public Trade() {
    }
    
    public Trade(String id, String tradeId, String counterparty, BigDecimal amount, 
                 String currency, LocalDate settlementDate, String status, Priority priority) {
        this.id = id;
        this.tradeId = tradeId;
        this.counterparty = counterparty;
        this.amount = amount;
        this.currency = currency;
        this.settlementDate = settlementDate;
        this.status = status;
        this.priority = priority;
        this.createdAt = Instant.now();
    }
    
    // Getters and setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    
    public String getCounterparty() {
        return counterparty;
    }
    
    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    
    public LocalDate getSettlementDate() {
        return settlementDate;
    }
    
    public void setSettlementDate(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public Priority getPriority() {
        return priority;
    }
    
    public void setPriority(Priority priority) {
        this.priority = priority;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getProcessedAt() {
        return processedAt;
    }
    
    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
    
    public String getProcessedBy() {
        return processedBy;
    }
    
    public void setProcessedBy(String processedBy) {
        this.processedBy = processedBy;
    }
}

