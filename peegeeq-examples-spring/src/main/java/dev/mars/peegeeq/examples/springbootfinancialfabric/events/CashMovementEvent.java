package dev.mars.peegeeq.examples.springbootfinancialfabric.events;

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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cash movement event.
 * 
 * Represents a cash movement in the treasury domain.
 */
public class CashMovementEvent {
    
    private final String movementId;
    private final String tradeId;
    private final BigDecimal amount;
    private final String currency;
    private final String account;
    private final String movementType;  // DEBIT, CREDIT
    private final Instant movementTime;
    
    @JsonCreator
    public CashMovementEvent(
            @JsonProperty("movementId") String movementId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("account") String account,
            @JsonProperty("movementType") String movementType,
            @JsonProperty("movementTime") Instant movementTime) {
        this.movementId = movementId;
        this.tradeId = tradeId;
        this.amount = amount;
        this.currency = currency;
        this.account = account;
        this.movementType = movementType;
        this.movementTime = movementTime;
    }
    
    public String getMovementId() {
        return movementId;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public String getAccount() {
        return account;
    }
    
    public String getMovementType() {
        return movementType;
    }
    
    public Instant getMovementTime() {
        return movementTime;
    }
}

