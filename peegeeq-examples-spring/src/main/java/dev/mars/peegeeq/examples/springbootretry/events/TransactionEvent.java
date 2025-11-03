package dev.mars.peegeeq.examples.springbootretry.events;

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
 * Transaction event for Retry example.
 * 
 * Plain POJO with Jackson annotations for JSON serialization.
 */
public class TransactionEvent {
    
    private final String transactionId;
    private final String accountId;
    private final BigDecimal amount;
    private final String type;
    private final String failureType; // For testing: "TRANSIENT", "PERMANENT", or null
    
    @JsonCreator
    public TransactionEvent(
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("type") String type,
            @JsonProperty("failureType") String failureType) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.failureType = failureType;
    }
    
    // Convenience constructor without failureType
    public TransactionEvent(String transactionId, String accountId, BigDecimal amount, String type) {
        this(transactionId, accountId, amount, type, null);
    }
    
    // Getters
    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getType() { return type; }
    public String getFailureType() { return failureType; }
    
    @Override
    public String toString() {
        return "TransactionEvent{" +
                "transactionId='" + transactionId + '\'' +
                ", accountId='" + accountId + '\'' +
                ", amount=" + amount +
                ", type='" + type + '\'' +
                ", failureType='" + failureType + '\'' +
                '}';
    }
}

