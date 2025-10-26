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

import java.time.Instant;

/**
 * Regulatory report event.
 * 
 * Represents a regulatory report in the regulatory domain.
 */
public class RegulatoryReportEvent {
    
    private final String reportId;
    private final String tradeId;
    private final String reportType;  // MIFID_II, EMIR, etc.
    private final String regulatoryRegime;
    private final Instant reportTime;
    
    @JsonCreator
    public RegulatoryReportEvent(
            @JsonProperty("reportId") String reportId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("reportType") String reportType,
            @JsonProperty("regulatoryRegime") String regulatoryRegime,
            @JsonProperty("reportTime") Instant reportTime) {
        this.reportId = reportId;
        this.tradeId = tradeId;
        this.reportType = reportType;
        this.regulatoryRegime = regulatoryRegime;
        this.reportTime = reportTime;
    }
    
    public String getReportId() {
        return reportId;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public String getReportType() {
        return reportType;
    }
    
    public String getRegulatoryRegime() {
        return regulatoryRegime;
    }
    
    public Instant getReportTime() {
        return reportTime;
    }
}

