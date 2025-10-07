package dev.mars.peegeeq.examples.springboot2bitemporal.model;

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

/**
 * Settlement instruction status enumeration.
 * 
 * Represents the lifecycle states of a settlement instruction in back office processing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public enum SettlementStatus {
    
    /**
     * Settlement instruction has been submitted to custodian.
     */
    SUBMITTED("Settlement instruction submitted to custodian"),
    
    /**
     * Settlement instruction has been matched with counterparty instruction.
     */
    MATCHED("Matched with counterparty instruction"),
    
    /**
     * Settlement has been confirmed by custodian.
     */
    CONFIRMED("Settlement confirmed by custodian"),
    
    /**
     * Settlement has failed (e.g., insufficient cash, securities unavailable).
     */
    FAILED("Settlement failed"),
    
    /**
     * Settlement instruction data has been corrected.
     */
    CORRECTED("Settlement instruction corrected");
    
    private final String description;
    
    SettlementStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}

