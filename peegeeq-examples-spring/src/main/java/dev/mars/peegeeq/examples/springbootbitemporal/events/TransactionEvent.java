package dev.mars.peegeeq.examples.springbootbitemporal.events;

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
import java.util.Objects;

/**
 * Represents a financial transaction event in the bi-temporal event store.
 * 
 * <p>This event captures all details of a financial transaction including:
 * <ul>
 *   <li>Transaction identifier</li>
 *   <li>Account identifier</li>
 *   <li>Transaction amount</li>
 *   <li>Transaction type (CREDIT or DEBIT)</li>
 *   <li>Description and metadata</li>
 * </ul>
 * 
 * <p>The event is immutable and serialized to JSONB in PostgreSQL.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
public class TransactionEvent {
    
    private final String transactionId;
    private final String accountId;
    private final BigDecimal amount;
    private final TransactionType type;
    private final String description;
    private final String reference;
    
    /**
     * Transaction type enumeration.
     */
    public enum TransactionType {
        CREDIT,  // Money added to account
        DEBIT    // Money removed from account
    }
    
    @JsonCreator
    public TransactionEvent(
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("type") TransactionType type,
            @JsonProperty("description") String description,
            @JsonProperty("reference") String reference) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.description = description;
        this.reference = reference;
    }
    
    public String getTransactionId() {
        return transactionId;
    }
    
    public String getAccountId() {
        return accountId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public TransactionType getType() {
        return type;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getReference() {
        return reference;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionEvent that = (TransactionEvent) o;
        return Objects.equals(transactionId, that.transactionId) &&
               Objects.equals(accountId, that.accountId) &&
               Objects.equals(amount, that.amount) &&
               type == that.type &&
               Objects.equals(description, that.description) &&
               Objects.equals(reference, that.reference);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(transactionId, accountId, amount, type, description, reference);
    }
    
    @Override
    public String toString() {
        return "TransactionEvent{" +
               "transactionId='" + transactionId + '\'' +
               ", accountId='" + accountId + '\'' +
               ", amount=" + amount +
               ", type=" + type +
               ", description='" + description + '\'' +
               ", reference='" + reference + '\'' +
               '}';
    }
}

