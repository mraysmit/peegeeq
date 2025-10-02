package dev.mars.peegeeq.examples.springbootdlq.events;

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

/**
 * Payment event for DLQ example.
 * 
 * Plain POJO with Jackson annotations for JSON serialization.
 */
public class PaymentEvent {
    
    private final String paymentId;
    private final String orderId;
    private final BigDecimal amount;
    private final String currency;
    private final String paymentMethod;
    private final boolean shouldFail; // For testing failure scenarios
    
    @JsonCreator
    public PaymentEvent(
            @JsonProperty("paymentId") String paymentId,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("paymentMethod") String paymentMethod,
            @JsonProperty("shouldFail") boolean shouldFail) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.shouldFail = shouldFail;
    }
    
    // Convenience constructor without shouldFail
    public PaymentEvent(String paymentId, String orderId, BigDecimal amount, String currency, String paymentMethod) {
        this(paymentId, orderId, amount, currency, paymentMethod, false);
    }
    
    // Getters
    public String getPaymentId() { return paymentId; }
    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPaymentMethod() { return paymentMethod; }
    public boolean isShouldFail() { return shouldFail; }
    
    @Override
    public String toString() {
        return "PaymentEvent{" +
                "paymentId='" + paymentId + '\'' +
                ", orderId='" + orderId + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", paymentMethod='" + paymentMethod + '\'' +
                ", shouldFail=" + shouldFail +
                '}';
    }
}

