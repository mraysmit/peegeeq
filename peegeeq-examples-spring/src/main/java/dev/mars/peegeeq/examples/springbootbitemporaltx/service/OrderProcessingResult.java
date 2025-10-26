package dev.mars.peegeeq.examples.springbootbitemporaltx.service;

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
import java.util.Objects;

/**
 * Order Processing Result for Multi-Event Store Transaction Coordination.
 * 
 * This class represents the result of processing an order through coordinated
 * transactions across multiple bi-temporal event stores.
 * 
 * <h2>Result Information</h2>
 * <ul>
 *   <li><b>Processing Status</b> - SUCCESS, FAILED, or PARTIAL</li>
 *   <li><b>Correlation Information</b> - IDs for tracking and audit</li>
 *   <li><b>Timing Information</b> - When the processing occurred</li>
 *   <li><b>Error Details</b> - Failure reasons and diagnostic information</li>
 * </ul>
 * 
 * <h2>Transaction Tracking</h2>
 * <ul>
 *   <li><b>Correlation ID</b> - Links all related events across stores</li>
 *   <li><b>Transaction ID</b> - Identifies the coordinated transaction</li>
 *   <li><b>Valid Time</b> - Business time when processing occurred</li>
 *   <li><b>Audit Trail</b> - Complete traceability for compliance</li>
 * </ul>
 * 
 * <h2>Status Values</h2>
 * <ul>
 *   <li><b>SUCCESS</b> - All event stores updated successfully</li>
 *   <li><b>FAILED</b> - Transaction rolled back due to failure</li>
 *   <li><b>PARTIAL</b> - Some operations succeeded (compensation needed)</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
public class OrderProcessingResult {
    
    private final String orderId;
    private final String status;
    private final String message;
    private final String correlationId;
    private final String transactionId;
    private final Instant validTime;
    
    @JsonCreator
    public OrderProcessingResult(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("validTime") Instant validTime) {
        this.orderId = orderId;
        this.status = status;
        this.message = message;
        this.correlationId = correlationId;
        this.transactionId = transactionId;
        this.validTime = validTime;
    }
    
    // Getters
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public String getTransactionId() {
        return transactionId;
    }
    
    public Instant getValidTime() {
        return validTime;
    }
    
    /**
     * Checks if the processing was successful.
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
    
    /**
     * Checks if the processing failed.
     */
    public boolean isFailed() {
        return "FAILED".equals(status);
    }
    
    /**
     * Checks if the processing was partial (some operations succeeded).
     */
    public boolean isPartial() {
        return "PARTIAL".equals(status);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderProcessingResult that = (OrderProcessingResult) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(status, that.status) &&
               Objects.equals(message, that.message) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(transactionId, that.transactionId) &&
               Objects.equals(validTime, that.validTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, status, message, correlationId, transactionId, validTime);
    }
    
    @Override
    public String toString() {
        return "OrderProcessingResult{" +
                "orderId='" + orderId + '\'' +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", validTime=" + validTime +
                '}';
    }
}
