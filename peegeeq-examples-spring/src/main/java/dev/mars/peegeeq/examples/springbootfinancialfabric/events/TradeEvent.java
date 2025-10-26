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
 * Trade event for equity trades.
 * 
 * Represents a trade in the trading domain.
 */
public class TradeEvent {
    
    private final String tradeId;
    private final String instrument;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final String counterparty;
    private final String assetClass;  // EQUITIES, FX, BONDS
    private final String tradeType;   // BUY, SELL
    private final Instant tradeTime;
    
    @JsonCreator
    public TradeEvent(
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("assetClass") String assetClass,
            @JsonProperty("tradeType") String tradeType,
            @JsonProperty("tradeTime") Instant tradeTime) {
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.quantity = quantity;
        this.price = price;
        this.counterparty = counterparty;
        this.assetClass = assetClass;
        this.tradeType = tradeType;
        this.tradeTime = tradeTime;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public String getInstrument() {
        return instrument;
    }
    
    public BigDecimal getQuantity() {
        return quantity;
    }
    
    public BigDecimal getPrice() {
        return price;
    }
    
    public String getCounterparty() {
        return counterparty;
    }
    
    public String getAssetClass() {
        return assetClass;
    }
    
    public String getTradeType() {
        return tradeType;
    }
    
    public Instant getTradeTime() {
        return tradeTime;
    }
    
    @com.fasterxml.jackson.annotation.JsonIgnore
    public BigDecimal getNotional() {
        if (quantity == null || price == null) {
            return null;
        }
        return quantity.multiply(price);
    }
}

