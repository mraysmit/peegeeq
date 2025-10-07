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
 * Position update event.
 * 
 * Represents a position update in the position management domain.
 */
public class PositionUpdateEvent {
    
    private final String updateId;
    private final String tradeId;
    private final String instrument;
    private final String account;
    private final BigDecimal quantityChange;
    private final BigDecimal newPosition;
    private final Instant updateTime;
    
    @JsonCreator
    public PositionUpdateEvent(
            @JsonProperty("updateId") String updateId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("account") String account,
            @JsonProperty("quantityChange") BigDecimal quantityChange,
            @JsonProperty("newPosition") BigDecimal newPosition,
            @JsonProperty("updateTime") Instant updateTime) {
        this.updateId = updateId;
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.account = account;
        this.quantityChange = quantityChange;
        this.newPosition = newPosition;
        this.updateTime = updateTime;
    }
    
    public String getUpdateId() {
        return updateId;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public String getInstrument() {
        return instrument;
    }
    
    public String getAccount() {
        return account;
    }
    
    public BigDecimal getQuantityChange() {
        return quantityChange;
    }
    
    public BigDecimal getNewPosition() {
        return newPosition;
    }
    
    public Instant getUpdateTime() {
        return updateTime;
    }
}

