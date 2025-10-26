package dev.mars.peegeeq.examples.springboot.model;

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
 * Response object for order creation operations.
 * 
 * This class represents the response data for order creation
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * Success response JSON:
 * <pre>
 * {
 *   "orderId": "ORDER-12345-67890",
 *   "status": "CREATED",
 *   "message": "Order created successfully",
 *   "timestamp": "2025-09-06T10:30:00Z"
 * }
 * </pre>
 * 
 * Error response JSON:
 * <pre>
 * {
 *   "orderId": null,
 *   "status": "ERROR",
 *   "message": "Order creation failed: Invalid customer ID",
 *   "timestamp": "2025-09-06T10:30:00Z"
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class CreateOrderResponse {
    private final String orderId;
    private final String status;
    private final String message;
    private final Instant timestamp;

    /**
     * Creates a successful order creation response.
     * 
     * @param orderId The created order ID
     */
    public CreateOrderResponse(String orderId) {
        this(orderId, "CREATED", "Order created successfully");
    }

    /**
     * Creates an error order creation response.
     * 
     * @param orderId The order ID (may be null for errors)
     * @param message The error message
     */
    public CreateOrderResponse(String orderId, String message) {
        this(orderId, orderId != null ? "CREATED" : "ERROR", message);
    }

    /**
     * Creates a complete order creation response.
     * 
     * @param orderId The order ID
     * @param status The operation status
     * @param message The response message
     */
    @JsonCreator
    public CreateOrderResponse(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message) {
        this.orderId = orderId;
        this.status = status != null ? status : (orderId != null ? "CREATED" : "ERROR");
        this.message = message != null ? message : "Order operation completed";
        this.timestamp = Instant.now();
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * Checks if the response indicates success.
     * 
     * @return true if the operation was successful
     */
    public boolean isSuccess() {
        return orderId != null && !"ERROR".equals(status);
    }

    /**
     * Checks if the response indicates an error.
     * 
     * @return true if the operation failed
     */
    public boolean isError() {
        return "ERROR".equals(status);
    }

    @Override
    public String toString() {
        return String.format("CreateOrderResponse{orderId='%s', status='%s', message='%s', timestamp=%s}", 
            orderId, status, message, timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        CreateOrderResponse that = (CreateOrderResponse) o;
        
        return (orderId != null ? orderId.equals(that.orderId) : that.orderId == null) &&
               status.equals(that.status) &&
               message.equals(that.message);
    }

    @Override
    public int hashCode() {
        int result = orderId != null ? orderId.hashCode() : 0;
        result = 31 * result + status.hashCode();
        result = 31 * result + message.hashCode();
        return result;
    }
}
