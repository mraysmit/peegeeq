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
import java.time.LocalDate;

/**
 * Settlement instruction event.
 * 
 * Represents a settlement instruction in the custody domain.
 */
public class SettlementInstructionEvent {
    
    private final String instructionId;
    private final String tradeId;
    private final String instrument;
    private final BigDecimal quantity;
    private final String custodian;
    private final LocalDate settlementDate;
    private final Instant instructionTime;
    
    @JsonCreator
    public SettlementInstructionEvent(
            @JsonProperty("instructionId") String instructionId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("custodian") String custodian,
            @JsonProperty("settlementDate") LocalDate settlementDate,
            @JsonProperty("instructionTime") Instant instructionTime) {
        this.instructionId = instructionId;
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.quantity = quantity;
        this.custodian = custodian;
        this.settlementDate = settlementDate;
        this.instructionTime = instructionTime;
    }
    
    public String getInstructionId() {
        return instructionId;
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
    
    public String getCustodian() {
        return custodian;
    }
    
    public LocalDate getSettlementDate() {
        return settlementDate;
    }
    
    public Instant getInstructionTime() {
        return instructionTime;
    }
}

